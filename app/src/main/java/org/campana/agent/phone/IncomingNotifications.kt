package org.campana.agent.phone

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.campana.agent.data.Channel
import org.campana.agent.data.sameContact
import java.security.MessageDigest

/** Only native direct-message evidence reaches the reply queue. No feed/comment fallback. */
internal object IncomingNotifications {
    fun parse(sbn:StatusBarNotification,now:Long=System.currentTimeMillis()):IncomingMessage? { return runCatching {
        val channel=Channel.forPackage(sbn.packageName) ?: return null
        val n=sbn.notification
        if(n.flags and Notification.FLAG_GROUP_SUMMARY!=0 || !IncomingNotificationRules.category(channel,n.category))return null
        val style=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return null
        if(style.isGroupConversation)return null
        val last=style.messages.lastOrNull() ?: return null
        val sender=last.person ?: return null // Android uses null for the device owner's messages.
        fun samePerson(a:Person,b:Person):Boolean = when {
            !a.key.isNullOrBlank() && !b.key.isNullOrBlank()->a.key==b.key
            !a.uri.isNullOrBlank() && !b.uri.isNullOrBlank()->a.uri==b.uri
            else->sameContact(a.name?.toString().orEmpty(),b.name?.toString().orEmpty())
        }
        if(samePerson(sender,style.user))return null
        val title=n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if(!sameContact(title,sender.name?.toString().orEmpty()))return null
        val audioMime=last.dataMimeType?.takeIf{it.startsWith("audio/") || it=="application/ogg"}.orEmpty()
        val audioUri=if(audioMime.isNotBlank())last.dataUri?.takeIf{it.scheme=="content"}?.toString().orEmpty() else ""
        val text=last.text?.toString()?.trim().orEmpty().ifBlank{if(audioMime.isNotBlank())"Mensaje de voz" else ""}
        val time=last.timestamp
        if(text.isBlank() || text.length>6000 || now-time !in 0..IncomingNotificationRules.MAX_PENDING_AGE_MILLIS)return null
        val digest=MessageDigest.getInstance("SHA-256").digest((channel.name+"|"+title+"|"+text+"|"+time).toByteArray())
            .joinToString(""){"%02x".format(it)}
        IncomingMessage(sbn.key,IncomingNotificationRules.fingerprint(channel,sbn.key,title,text+if(audioMime.isNotBlank())"\u0000"+audioMime+"\u0000"+audioUri else "",time),channel,title,text,time,direct=true,senderPhone=IncomingNotificationRules.phone(sender.uri),legacyFingerprint=digest,nativeAccount=if(channel==Channel.MESSENGER)style.user.name?.toString().orEmpty() else "",audioMime=audioMime,audioUri=audioUri)
    }.getOrNull() }
}
