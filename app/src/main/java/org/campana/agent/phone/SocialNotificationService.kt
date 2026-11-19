package org.campana.agent.phone

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import org.campana.agent.CampaignApp
import org.campana.agent.data.Channel
import java.util.concurrent.ConcurrentHashMap

class SocialNotificationService : NotificationListenerService() {
    override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>){
        if(org.campana.agent.BuildConfig.DEBUG && "--audio-inspection" in args) {
            active.values.forEach{(m,_)->writer.println("channel=${m.channel} voice=${m.voiceNote} mime=${m.audioMime} uri_available=${m.audioUri.isNotBlank()} placeholder=${VoiceMessageRules.placeholder(m.text)}")}
            activeNotifications?.filter{Channel.forPackage(it.packageName)!=null}?.forEach{s->val last=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(s.notification)?.messages?.lastOrNull();writer.println("raw_package=${s.packageName} category=${s.notification.category} summary=${s.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0} mime=${last?.dataMimeType} uri_available=${last?.dataUri!=null} sender_present=${last?.person!=null} text=${last?.text?.toString()?.take(80)}")}
        }
    }
    companion object {
        @Volatile var instance: SocialNotificationService? = null
            private set
        val connected=kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    private val active = ConcurrentHashMap<String,Pair<IncomingMessage,StatusBarNotification>>()
    override fun onListenerConnected() {
        instance=this
        connected.value=true
        activeNotifications?.forEach { onNotificationPosted(it) }
        org.campana.agent.jobs.TaskWorker.kick(this)
        (application as CampaignApp).graph.runtime.ensureStarted("notifications_connected")
    }
    override fun onListenerDisconnected() {
        instance=null;connected.value=false;active.clear()
        runCatching{requestRebind(android.content.ComponentName(this,SocialNotificationService::class.java))}
    }
    override fun onDestroy() { instance=null;connected.value=false;active.clear();super.onDestroy() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { active.remove(sbn.key) }
    fun reconsiderIncoming(){val graph=(application as CampaignApp).graph;active.values.forEach{pair->graph.scope.launch{graph.incoming(pair.first)}}}
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val graph = (application as CampaignApp).graph
        if(Channel.forPackage(sbn.packageName)==null)return
        val message=IncomingNotifications.parse(sbn) ?: run{active.remove(sbn.key);graph.trace("incoming_rejected package="+sbn.packageName+" category="+sbn.notification.category+" summary="+(sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY!=0));return}
        if(replyAction(sbn)==null && sbn.notification.contentIntent?.creatorPackage!=sbn.packageName){active.remove(sbn.key);graph.trace("incoming_rejected no_trusted_reply_or_open_action");return}
        active[sbn.key] = message to sbn
        graph.trace("incoming_detected channel="+message.channel+" age_seconds="+((System.currentTimeMillis()-message.timestamp)/1000)+" native_phone="+message.senderPhone.isNotBlank())
        graph.scope.launch { graph.incoming(message) }
    }
    private fun replyAction(sbn:StatusBarNotification):Notification.Action?=sbn.notification.actions?.firstOrNull { a ->
        a.actionIntent?.creatorPackage==sbn.packageName && a.remoteInputs?.any{it.allowFreeFormInput}==true
    }
    fun canReply(key:String,fingerprint:String)=active[key]?.let{it.first.fingerprint==fingerprint && replyAction(it.second)!=null}==true
    fun validateIncoming(key:String,fingerprint:String):IncomingMessage {
        check(!(application as CampaignApp).graph.paused.value) { "El agente está pausado." }
        val pair = active[key] ?: error("La notificación ya no está disponible.")
        check(System.currentTimeMillis()-pair.first.timestamp in 0..IncomingNotificationRules.MAX_PENDING_AGE_MILLIS) { "La notificación venció; espera un mensaje nuevo o revísala manualmente." }
        check(pair.first.fingerprint == fingerprint) { "La conversación recibió contenido nuevo; requiere una nueva evaluación." }
        val current = activeNotifications.firstOrNull { it.key == key } ?: error("La notificación fue retirada.")
        check(current.postTime == pair.second.postTime) { "La notificación cambió." }
        val fresh=IncomingNotifications.parse(current) ?: error("No es un mensaje directo entrante verificable.")
        check(fresh.fingerprint==fingerprint && fresh.direct){"El mensaje entrante cambió."}
        return fresh
    }
    fun validateReply(key:String,fingerprint:String):IncomingMessage=validateIncoming(key,fingerprint).also{check(canReply(key,fingerprint)){"Esta notificación no permite responder directamente."}}
    fun openIncoming(key:String,fingerprint:String) {
        validateIncoming(key,fingerprint)
        val current=activeNotifications.firstOrNull{it.key==key} ?: error("La notificación fue retirada.")
        val intent=checkNotNull(current.notification.contentIntent)
        check(intent.creatorPackage==current.packageName)
        checkNotNull(PhoneAccessibilityService.instance).requireControl()
        // Explicit sender opt-in is required by Android 14+. Only a trusted notification,
        // an authorized read task, an unlocked screen and a visible recovery lease reach this call.
        val options=android.app.ActivityOptions.makeBasic().apply {
            if(android.os.Build.VERSION.SDK_INT>=34)setPendingIntentBackgroundActivityStartMode(
                if(android.os.Build.VERSION.SDK_INT>=36)android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                else android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle()
        intent.send(this,0,null,null,null,null,options)
    }
    fun reply(key: String, fingerprint: String, text: String,epoch:Long) {
        validateReply(key,fingerprint)
        val pair=checkNotNull(active[key])
        val action=checkNotNull(replyAction(pair.second))
        (application as CampaignApp).graph.runtime.requireEpoch(epoch)
        val inputs = action.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs,fill,Bundle().apply { inputs.forEach { putCharSequence(it.resultKey,text) } })
        RemoteInput.setResultsSource(fill,RemoteInput.SOURCE_FREE_FORM_INPUT)
        action.actionIntent.send(this,0,fill)
        active.remove(key)
    }
    fun requireCampaignAccount(key:String,fingerprint:String,c:org.campana.agent.data.Campaign) {
        check(validateIncoming(key,fingerprint).matchesCampaignAccount(c)){"La cuenta receptora no corresponde a la fanpage de la campaña."}
    }
}
