package org.campana.agent.phone

import org.campana.agent.data.Campaign
import org.campana.agent.data.Channel

data class IncomingMessage(val key:String,val fingerprint:String,val channel:Channel,
    val title:String,val text:String,val timestamp:Long,val direct:Boolean=false,val senderPhone:String="",val legacyFingerprint:String="",val nativeAccount:String="",val audioMime:String="",val audioUri:String="") {
    val voiceNote get()=audioMime.startsWith("audio/") || audioMime=="application/ogg" || VoiceMessageRules.placeholder(text)
    fun matchesCampaignAccount(c:Campaign)=channel!=Channel.MESSENGER ||
        (c.accountIdentity.isNotBlank() && nativeAccount.isNotBlank() && org.campana.agent.data.sameContact(nativeAccount,c.accountIdentity))
    fun matches(recipient:String)=org.campana.agent.data.sameContact(title,recipient) ||
        (senderPhone.isNotBlank() && org.campana.agent.data.sameContact(senderPhone,recipient))
    fun recipientFor(c:Campaign):String?=if(c.isPublicInbox())title.takeIf{it.isNotBlank()} else c.recipients.lines().firstOrNull{it.trim()!="*" && matches(it)}?.trim()
        ?: title.takeIf{c.mode=="REACTIVE" && c.autoReply && c.recipients.trim()=="*"}
}

object VoiceMessageRules {
    fun placeholder(text:String)=Regex("^(?:(?:[\\p{So}\\p{Cf}\\s])+)?(?:(?:mensaje|nota) de voz|voice message|audio|clip de audio)(?:\\s*\\(?[0-9:]+\\)?\\s*)?$").matches(org.campana.agent.data.normalized(text))
}

internal object IncomingNotificationRules {
    // Only an active, unread notification can use this window; resume/context cutoffs still apply.
    const val MAX_PENDING_AGE_MILLIS=24*60*60_000L
    fun category(channel:Channel,category:String?)=category=="msg" || (channel in setOf(Channel.WHATSAPP,Channel.MESSENGER) && category==null)
    fun phone(uri:String?):String=uri?.takeIf{it.startsWith("tel:") }?.removePrefix("tel:")
        ?.takeIf{it.matches(Regex("\\+?[0-9 ()-]{8,24}"))} ?: ""
    fun fingerprint(channel:Channel,key:String,title:String,text:String,time:Long):String {
        val data=listOf(channel.name,key,title,text,time.toString()).joinToString(""){it.length.toString()+":"+it}
        return java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}

internal object IncomingRouting {
    fun campaign(campaigns:List<Campaign>,message:IncomingMessage,paused:Boolean,since:Long,now:Long):Campaign? {
        if(paused || !message.direct || message.timestamp<since || now-message.timestamp !in 0..IncomingNotificationRules.MAX_PENDING_AGE_MILLIS)return null
        return campaigns.filter{it.mode in setOf("AUTO","REACTIVE") && it.autoReply && message.channel in it.channelList() &&
            message.recipientFor(it)!=null && message.timestamp>=it.updatedAt}.singleOrNull()
    }
}
