package org.campana.agent.agent

import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import androidx.room.withTransaction
import org.campana.agent.AppGraph
import org.campana.agent.data.*
import org.campana.agent.net.*
import org.campana.agent.phone.*

/** Resolve a native conversation to an already-authorized phone number. Never grants a new recipient. */
class IncomingIdentities(private val g:AppGraph) {
    suspend fun resumePending() {
        if(g.paused.value || !g.incomingWake.value)return
        for(c in g.dao.campaigns())for(task in g.dao.tasks(c.id).filter{it.status in setOf("WAITING_OWNER","WAITING_UNLOCK","WAITING_PERMISSION")}) {
            val safe=if(task.kind=="VERIFY_CONTACT")runCatching{authorized(task,c)}.isSuccess
                else task.kind=="REPLY" && runCatching{uiReplySource(task)!=null}.getOrDefault(false) && g.dao.allReceipts(c.id).none{it.taskId==task.id && it.action=="external_submit"}
            if(safe && g.dao.queueReviewed(task.id,System.currentTimeMillis())==1)org.campana.agent.jobs.TaskWorker.schedule(g.context,task.copy(status="QUEUED"),reviewed=true)
        }
    }
    suspend fun open(task:AgentTask) {
        authorized(task,checkNotNull(g.dao.campaign(task.campaignId)))
        val opened=withContext(Dispatchers.Main) {
            runCatching{checkNotNull(SocialNotificationService.instance).openIncoming(task.notificationKey,task.notificationFingerprint)}.isSuccess
        }
        if(!opened) {
            val receipts=g.dao.allReceipts(task.campaignId)
            check(receipts.any{it.taskId==task.id && it.action=="agent_visual_observation" && it.status=="OBSERVED" && json.parseToJsonElement(it.detail).jsonObject.str("package")=="com.whatsapp"}){"La notificación ya no está disponible y no hay una sesión de lectura previa de esa conversación."}
            withContext(Dispatchers.Main) {
                checkNotNull(PhoneAccessibilityService.instance).requireControl()
                g.context.startActivity(checkNotNull(SocialApps.launchIntent(g.context,Channel.WHATSAPP)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    suspend fun enrich(message:IncomingMessage):IncomingMessage {
        if(message.senderPhone.isNotBlank())return message
        for(c in g.dao.campaigns()) {
            val proof=g.dao.allReceipts(c.id).asReversed().firstNotNullOfOrNull { r->
                if(r.action!="incoming_identity_verified" || r.status!="OBSERVED")null else runCatching {
                    val d=json.parseToJsonElement(r.detail).jsonObject
                    d.str("recipient").takeIf{d.str("conversation_key")==message.key && d.str("channel")==message.channel.name && sameContact(d.str("display_name"),message.title) && c.allowsIncomingRecipient(it)}
                }.getOrNull()
            }
            if(proof!=null)return message.copy(senderPhone=proof)
        }
        return message
    }
    suspend fun request(message:IncomingMessage,campaigns:List<Campaign>) {
        if(g.paused.value || !g.incomingWake.value || !message.direct || message.channel!=Channel.WHATSAPP)return
        val now=System.currentTimeMillis()
        if(message.timestamp<g.prefs.getLong("reply_listen_since",0) || now-message.timestamp !in 0..IncomingNotificationRules.MAX_PENDING_AGE_MILLIS)return
        val candidates=campaigns.filter{ContactIdentityRules.candidate(it,message.title) && message.timestamp>=it.updatedAt}
        val c=candidates.singleOrNull() ?: return
        val existing=g.dao.taskForDedupe("identify:"+message.fingerprint)
        if(existing!=null) {
            if(existing.kind=="VERIFY_CONTACT" && existing.status in setOf("WAITING_OWNER","WAITING_PERMISSION","WAITING_UNLOCK") && g.dao.queueReviewed(existing.id,now)==1)
                org.campana.agent.jobs.TaskWorker.schedule(g.context,existing.copy(status="QUEUED"),reviewed=true)
            return
        }
        val task=AgentTask(campaignId=c.id,channel="WHATSAPP",kind="VERIFY_CONTACT",recipient=message.title,
            notificationKey=message.key,notificationFingerprint=message.fingerprint,dedupeKey="identify:"+message.fingerprint,
            instruction="Verifica en el perfil nativo si esta conversación corresponde a uno de los números ya autorizados de la campaña. Lee el número real y usa verify_incoming_contact. No envíes mensajes ni cambies datos o permisos. Decide la navegación a partir de las pantallas observadas.")
        val inserted=g.db.withTransaction {
            if(g.dao.insert(task)==-1L)false else {
                g.dao.insert(Receipt(campaignId=c.id,taskId=task.id,action="incoming_identity_source",status="OBSERVED",detail=buildJsonObject {
                    put("key",message.key);put("fingerprint",message.fingerprint);put("title",message.title);put("text",message.text);put("timestamp",message.timestamp);put("context_updated_at",c.updatedAt)
                }.toString()));true
            }
        }
        if(inserted)org.campana.agent.jobs.TaskWorker.schedule(g.context,task)
        g.trace("incoming_identity_check_queued")
    }
    suspend fun authorized(task:AgentTask,c:Campaign) {
        check(task.kind=="VERIFY_CONTACT" && task.channel=="WHATSAPP" && !g.paused.value && g.incomingWake.value)
        check(ContactIdentityRules.candidate(c,task.recipient))
        val message=source(task,c)
        check(message.direct && message.channel==Channel.WHATSAPP && sameContact(message.title,task.recipient))
        check(message.timestamp>=maxOf(c.updatedAt,g.prefs.getLong("reply_listen_since",0)))
    }
    private suspend fun source(task:AgentTask,c:Campaign):IncomingMessage {
        val r=g.dao.allReceipts(c.id).single{it.taskId==task.id && it.action=="incoming_identity_source" && it.status=="OBSERVED"}
        val d=json.parseToJsonElement(r.detail).jsonObject
        check(d["context_updated_at"]?.jsonPrimitive?.long==c.updatedAt)
        check(d.str("key")==task.notificationKey && d.str("fingerprint")==task.notificationFingerprint)
        val time=checkNotNull(d["timestamp"]?.jsonPrimitive?.long)
        check(time>=maxOf(c.updatedAt,g.prefs.getLong("reply_listen_since",0)) && System.currentTimeMillis()-time in 0..IncomingNotificationRules.MAX_PENDING_AGE_MILLIS)
        return IncomingMessage(task.notificationKey,task.notificationFingerprint,Channel.WHATSAPP,d.str("title"),d.str("text"),time,direct=true)
    }
    suspend fun uiReplySource(task:AgentTask):IncomingMessage? {
        g.inbox.source(task)?.let{return it}
        g.visualIncoming.source(task)?.let{return it}
        if(task.kind!="REPLY" || task.channel!="WHATSAPP" || task.notificationKey.isBlank())return null
        val c=checkNotNull(g.dao.campaign(task.campaignId))
        val receipts=g.dao.allReceipts(c.id)
        val r=receipts.singleOrNull{it.taskId==task.id && it.action=="incoming_ui_reply_source" && it.status=="OBSERVED"} ?: return null
        val parent=checkNotNull(g.dao.task(json.parseToJsonElement(r.detail).jsonObject.str("parent_task_id")))
        check(parent.campaignId==c.id && parent.kind=="VERIFY_CONTACT" && parent.status=="COMPLETED")
        val message=source(parent,c)
        check(message.key==task.notificationKey && message.fingerprint==task.notificationFingerprint && message.text==task.instruction)
        val proof=receipts.single{it.taskId==parent.id && it.action=="incoming_identity_verified" && it.status=="OBSERVED"}
        val p=json.parseToJsonElement(proof.detail).jsonObject
        check(p.str("conversation_key")==message.key && sameContact(p.str("display_name"),message.title) && sameContact(p.str("recipient"),task.recipient) && c.allowsIncomingRecipient(task.recipient))
        return message.copy(senderPhone=task.recipient)
    }
    suspend fun enqueueVerifiedReply(parent:AgentTask) {
        val c=checkNotNull(g.dao.campaign(parent.campaignId));val message=enrich(source(parent,c))
        val recipient=checkNotNull(message.recipientFor(c))
        val reply=AgentTask(campaignId=c.id,channel="WHATSAPP",kind="REPLY",recipient=recipient,instruction=message.text,
            dedupeKey=message.fingerprint,notificationKey=message.key,notificationFingerprint=message.fingerprint)
        val inserted=g.db.withTransaction {
            if(g.dao.insert(reply)==-1L)false else {
                g.dao.insert(Receipt(campaignId=c.id,taskId=reply.id,action="incoming_ui_reply_source",status="OBSERVED",detail=buildJsonObject{put("parent_task_id",parent.id)}.toString()));true
            }
        }
        if(inserted)org.campana.agent.jobs.TaskWorker.schedule(g.context,reply)
    }
    suspend fun act(task:AgentTask,service:PhoneAccessibilityService,state:ScreenState,node:ScreenNode,action:String):Boolean {
        authorized(task,checkNotNull(g.dao.campaign(task.campaignId)))
        return withContext(Dispatchers.Main) {
            service.requireControl()
            val fresh=service.observe()
            check(fresh.packageName=="com.whatsapp" && fresh.windowId==state.windowId)
            val n=PostLinkRules.liveNode(fresh.nodes,node) ?: error("El elemento cambió; observa de nuevo.")
            check(action in setOf("click","scroll","scroll_back"))
            if(action=="click")check(ContactIdentityRules.navigation(n.text.ifBlank{n.description})){"La verificación solo permite leer el perfil; no envía, llama, edita ni cambia ajustes."}
            else check(n.scrollable)
            service.uncoverTarget(Rect(n.left,n.top,n.right,n.bottom))
            service.act(fresh.id,n.id,action,"")
        }
    }
    suspend fun verified(taskId:String)=g.dao.allReceipts(checkNotNull(g.dao.task(taskId)).campaignId).any{it.taskId==taskId && it.action=="incoming_identity_verified"}
    suspend fun verify(task:AgentTask):String {
        val c=checkNotNull(g.dao.campaign(task.campaignId));authorized(task,c)
        val state=withContext(Dispatchers.Main){checkNotNull(PhoneAccessibilityService.instance).observe()}
        check(state.packageName=="com.whatsapp")
        val phone=ContactIdentityRules.profilePhone(state.nodes,task.recipient,c.recipients.lines())
        if(phone==null) {
            g.trace("identity_profile_not_verified fields="+state.nodes.filter{it.resource.isNotBlank()}.map{it.resource.substringAfterLast('/')}.distinct().joinToString(",").take(450))
            error("No hay evidencia nativa suficiente del nombre y número autorizados en el perfil. Continúa observando; no envíes ni concedas permisos.")
        }
        g.dao.insert(Receipt(campaignId=c.id,taskId=task.id,action="incoming_identity_verified",status="OBSERVED",detail=buildJsonObject {
            put("conversation_key",task.notificationKey);put("channel",task.channel);put("display_name",task.recipient);put("recipient",phone)
        }.toString()))
        g.trace("incoming_identity_verified")
        return "El perfil nativo coincide con un número ya autorizado. La app puede reconsiderar el mensaje entrante."
    }
}

internal object ContactIdentityRules {
    fun candidate(c:Campaign,title:String):Boolean {
        if(c.mode!="AUTO" || !c.autoReply || Channel.WHATSAPP !in c.channelList() || c.recipients.trim()=="*")return false
        val allowed=c.recipients.lines().map{it.trim()}.filter{it.isNotBlank()}
        return allowed.any{IncomingNotificationRules.phone("tel:"+it).isNotBlank()} && allowed.any {
            !it.any(Char::isDigit) && normalized(title).startsWith(normalized(it)+" ")
        }
    }
    fun navigation(label:String):Boolean {
        val s=normalized(label)
        return s.isNotBlank() && !Regex("enviar|send|llamar|call|videollamada|video call|editar|edit|eliminar|delete|borrar|remove|bloquear|block|reportar|report|silenciar|mute|notificacion|notification|guardar|save|compartir|share|crear|create|anadir|add|agregar|favorit|disappear|temporales|seguridad|security|pagar|pay").containsMatchIn(s) && s !in setOf("mensaje","message","audio","video")
    }
    fun profilePhone(nodes:List<ScreenNode>,displayName:String,allowed:List<String>):String? {
        if(nodes.any{it.editable || it.resource=="com.whatsapp:id/conversation_contact_name"})return null
        val nativeCards=nodes.any{it.resource=="com.whatsapp:id/contact_details_card"} && nodes.any{it.resource=="com.whatsapp:id/top_info_card"}
        val name=nodes.any{sameContact(it.text,displayName) && it.resource.startsWith("com.whatsapp:id/") && Regex("contact.*name|contact.*title|name.*contact|name|collapsing_toolbar").matches(it.resource.substringAfterLast('/')) && (it.resource!="com.whatsapp:id/contact_title" || nativeCards)}
        if(!name)return null
        val numbers=nodes.filter{it.enabled && it.resource.startsWith("com.whatsapp:id/") && (Regex("contact_info_(?:phone_number|number|data)|phone_number(?:_text|_tv)?").matches(it.resource.substringAfterLast('/')) || (nativeCards && it.resource=="com.whatsapp:id/contact_subtitle"))}
        return allowed.filter{IncomingNotificationRules.phone("tel:"+it).isNotBlank()}.singleOrNull{approved->numbers.any{sameContact(it.text,approved)}}
    }
}
