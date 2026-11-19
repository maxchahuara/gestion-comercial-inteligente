package org.campana.agent.phone

import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.campana.agent.AppGraph
import org.campana.agent.data.*
import org.campana.agent.net.*

/** Genuine native message evidence for apps/alerts that do not expose inline Responder. */
class IncomingVisualReplies(private val g:AppGraph) {
    suspend fun record(task:AgentTask,message:IncomingMessage) {
        withContext(Dispatchers.Main){checkNotNull(SocialNotificationService.instance).validateIncoming(message.key,message.fingerprint)}
        if(g.dao.allReceipts(task.campaignId).any{it.taskId==task.id && it.action=="native_visual_incoming"})return
        val c=checkNotNull(g.dao.campaign(task.campaignId))
        g.dao.insert(Receipt(campaignId=c.id,taskId=task.id,action="native_visual_incoming",status="OBSERVED",detail=buildJsonObject {
            put("key",message.key);put("fingerprint",message.fingerprint);put("channel",message.channel.name)
            put("title",message.title);put("text",message.text);put("timestamp",message.timestamp);put("context_updated_at",c.updatedAt);put("native_account",message.nativeAccount)
            put("audio_uri",message.audioUri);put("audio_mime",message.audioMime)
        }.toString()))
        g.trace("incoming_visual_fallback_recorded channel="+message.channel)
    }
    suspend fun source(task:AgentTask):IncomingMessage? {
        if(task.kind!="REPLY" || task.notificationKey.isBlank())return null
        val r=g.dao.allReceipts(task.campaignId).singleOrNull{it.taskId==task.id && it.action=="native_visual_incoming" && it.status=="OBSERVED"} ?: return null
        val d=json.parseToJsonElement(r.detail).jsonObject;val c=checkNotNull(g.dao.campaign(task.campaignId))
        check(d.str("key")==task.notificationKey && d.str("fingerprint")==task.notificationFingerprint && d.str("text")==task.instruction && d.str("channel")==task.channel)
        check(d["context_updated_at"]?.jsonPrimitive?.long==c.updatedAt)
        val time=checkNotNull(d["timestamp"]?.jsonPrimitive?.long)
        check(System.currentTimeMillis()-time in 0..IncomingNotificationRules.MAX_PENDING_AGE_MILLIS)
        return IncomingMessage(task.notificationKey,task.notificationFingerprint,Channel.valueOf(task.channel),d.str("title"),task.instruction,time,direct=true,nativeAccount=d.str("native_account"),audioMime=d.str("audio_mime"),audioUri=d.str("audio_uri")).also{check(it.matchesCampaignAccount(c)){"La notificación no identifica la fanpage autorizada."}}
    }
    suspend fun open(task:AgentTask) {
        checkNotNull(source(task))
        val opened=withContext(Dispatchers.Main){runCatching{checkNotNull(SocialNotificationService.instance).openIncoming(task.notificationKey,task.notificationFingerprint)}.isSuccess}
        if(!opened) {
            check(g.dao.allReceipts(task.campaignId).any{it.taskId==task.id && it.action=="agent_visual_observation" && runCatching{g.runtime.gate.permits(json.parseToJsonElement(it.detail).jsonObject["session_epoch"]?.jsonPrimitive?.longOrNull)}.getOrDefault(false)}){"La notificación ya no está disponible y no se abrió esa conversación en esta sesión. Espera una alerta nueva para no utilizar otra cuenta o chat."}
            withContext(Dispatchers.Main){checkNotNull(PhoneAccessibilityService.instance).requireControl();g.context.startActivity(checkNotNull(SocialApps.launchIntent(g.context,Channel.valueOf(task.channel))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}
        }
    }
}
