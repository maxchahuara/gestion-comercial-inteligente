package org.campana.agent.phone

import android.graphics.Rect
import kotlinx.coroutines.*
import org.campana.agent.AppGraph
import org.campana.agent.data.*
import kotlinx.serialization.json.*
import org.campana.agent.net.*

/** A phone-owned, read-only account inspection never grants authority to send a message. */
class MessengerAccountInspection(private val g:AppGraph) {
    val verifiedNativeAccount=kotlinx.coroutines.flow.MutableStateFlow(false)
    suspend fun ensure() {
        if(g.paused.value)return
        val c=g.dao.campaign(g.activeCampaign.value) ?: return
        if(c.mode !in setOf("AUTO","REACTIVE") || !c.autoReply || Channel.MESSENGER !in c.channelList() || c.accountIdentity.isBlank())return
        val key="messenger-prepare-v030:"+c.id+":"+c.updatedAt+":"+g.runtime.gate.epoch
        val previous=g.dao.taskForDedupe(key)
        if(previous!=null){
            if(previous.status=="COMPLETED" && verifiedForTask(previous))verifiedNativeAccount.value=true
            if(previous.status in setOf("WAITING_OWNER","WAITING_UNLOCK","WAITING_PERMISSION") && g.dao.queueReviewed(previous.id,System.currentTimeMillis())==1){org.campana.agent.jobs.TaskWorker.schedule(g.context,previous.copy(status="QUEUED"),reviewed=true);g.runtime.dispatch(previous.id)}
            return
        }
        g.enqueue(AgentTask(campaignId=c.id,channel="MESSENGER",kind="PREPARE_INBOX",recipient=c.accountIdentity,dedupeKey=key,
            instruction="Prepara en Messenger la fanpage autorizada «${c.accountIdentity}» para atender sus mensajes. Diferencia el perfil personal administrador de la fanpage. Decide toda la navegación desde cada observación. Abre el selector de perfiles si hace falta y selecciona únicamente esa página. Debes comprobar el perfil actual mostrando su Menú y llamando a verify_messaging_account; ver la página en una lista o chat no basta. Después vuelve a la bandeja de la página. Si muestra Todas las notificaciones de esta página están desactivadas, se autoriza únicamente Activar ese aviso de la página verificada; no otros ajustes. Comprueba el resultado con verify_page_notifications. No termines afirmando preparación sin ese recibo. No abras conversaciones personales, no redactes, no envíes ni llames. Esta preparación no autoriza notificaciones de la cuenta personal."))
    }
    suspend fun act(task:AgentTask,service:PhoneAccessibilityService,state:ScreenState,node:ScreenNode,action:String):Boolean {
        check(task.kind in setOf("READ_ACCOUNT","PREPARE_INBOX","READ_INBOX") && task.channel=="MESSENGER")
        g.engine.authorized(task)
        require(action in setOf("click","scroll","scroll_back"))
        if(action=="click") {
            val labels=listOf(node.text,node.description).map{normalized(it).trim('¿','?',' ')}
            val safe=setOf("menu","cuentas","cambiar de cuenta","cambiar cuenta","cambiar de perfil","switch profile","switch account","switch accounts","administrar cuentas","cuentas empresariales","business accounts","ver todos los perfiles","see all profiles","perfiles","profiles","atras","back","volver")
            val selector=state.nodes.any{n->listOf(n.text,n.description).any{org.campana.agent.agent.MessengerAccountRules.selectorLabel(it) || normalized(it) in setOf("cuentas","perfiles","cuentas empresariales","business accounts")}}
            val menu=state.nodes.any{normalized(it.text)=="menu"}
            val profile=menu && labels.any{it.contains("cambiar de perfil") || it.contains("switch profile")}
            val activate=task.kind=="PREPARE_INBOX" && verifiedForTask(task) && org.campana.agent.agent.MessengerAccountRules.activatePageAlerts(state.nodes,node)
            val allowed=activate || profile || labels.any{it in safe || org.campana.agent.agent.MessengerAccountRules.menuTab(it) || org.campana.agent.agent.MessengerAccountRules.chatTab(it) || Regex("^(menu|cambiar de cuenta|switch accounts|business accounts)([ ·,:]|$)").containsMatchIn(it) || (selector && org.campana.agent.agent.MessengerAccountRules.pageChoice(it,task.recipient))} ||
                node.resource.substringAfterLast('/') in setOf("menu_tab","tab_menu","accounts_switcher","account_switcher","switch_account_button")
            if(!allowed && org.campana.agent.BuildConfig.DEBUG)android.util.Log.w("CampanaAccountQA","blocked expected=${task.recipient} selector=$selector resource=${node.resource} text=${node.text.take(100)} description=${node.description.take(160)} bounds=${node.left},${node.top},${node.right},${node.bottom}")
            if(!allowed)g.trace("account_navigation_blocked selector="+selector+" page_label="+labels.any{org.campana.agent.agent.MessengerAccountRules.pageChoice(it,task.recipient)}+" menu="+menu)
            check(allowed) {"La inspección solo puede abrir Menú, el selector de cuentas o la fanpage autorizada; nunca conversaciones personales."}
        } else check(node.scrollable)
        return withContext(Dispatchers.Main) {
            service.requireControl();val fresh=service.observe()
            check(fresh.packageName==Channel.MESSENGER.packageName && fresh.windowId==state.windowId)
            val live=org.campana.agent.agent.PostLinkRules.liveNode(fresh.nodes,node) ?: error("La pantalla cambió.")
            service.uncoverTarget(Rect(live.left,live.top,live.right,live.bottom))
            service.act(fresh.id,live.id,action,"")
        }
    }
    suspend fun verifyCurrent(task:AgentTask):String {
        check(task.kind in setOf("READ_ACCOUNT","PREPARE_INBOX","READ_INBOX"));g.engine.authorized(task)
        val state=withContext(Dispatchers.Main){checkNotNull(PhoneAccessibilityService.instance).observe()}
        val verified=state.packageName==Channel.MESSENGER.packageName && org.campana.agent.agent.MessengerAccountRules.activePageMenu(state.nodes,task.recipient)
        if(verified) {
            val c=checkNotNull(g.dao.campaign(task.campaignId))
            if(!verifiedForTask(task))g.dao.insert(Receipt(campaignId=task.campaignId,taskId=task.id,action="messenger_current_account",status="OBSERVED",detail=buildJsonObject{put("account",task.recipient);put("epoch",g.runtime.gate.epoch);put("context_updated_at",c.updatedAt)}.toString()))
            verifiedNativeAccount.value=true;g.trace("messenger_page_menu_verified")
            if(task.kind=="READ_INBOX")withContext(Dispatchers.Main){g.inbox.markPageVerified(task.id,checkNotNull(PhoneAccessibilityService.instance).controlToken())}
        }
        return "{\"current_account_matches_campaign\":$verified,\"personal_inbox_authorized\":false}"
    }
    private suspend fun verifiedForTask(task:AgentTask):Boolean {
        val c=g.dao.campaign(task.campaignId) ?: return false
        val r=g.dao.latestReceiptForTask(task.id,"messenger_current_account") ?: return false
        return r.status=="OBSERVED" && runCatching{val d=json.parseToJsonElement(r.detail).jsonObject;d.str("account")==c.accountIdentity && d.number("epoch")==g.runtime.gate.epoch && d.number("context_updated_at")==c.updatedAt}.getOrDefault(false)
    }
    suspend fun sessionVerified(c:Campaign):Boolean=g.dao.recentReceipts(c.id,"messenger_current_account").any{r->r.status=="OBSERVED" && runCatching{val d=json.parseToJsonElement(r.detail).jsonObject;d.str("account")==c.accountIdentity && d.number("epoch")==g.runtime.gate.epoch && d.number("context_updated_at")==c.updatedAt}.getOrDefault(false)}
    suspend fun verifyNotifications(task:AgentTask):String {
        check(task.kind=="PREPARE_INBOX");g.engine.authorized(task);check(verifiedForTask(task)){"Primero verifica la fanpage actual en Menú."}
        val state=withContext(Dispatchers.Main){checkNotNull(PhoneAccessibilityService.instance).observe()}
        val ready=state.packageName==Channel.MESSENGER.packageName && org.campana.agent.agent.MessengerAccountRules.pageInbox(state.nodes) && !org.campana.agent.agent.MessengerAccountRules.pageAlertsDisabled(state.nodes)
        if(ready){g.dao.insert(Receipt(campaignId=task.campaignId,taskId=task.id,action="messenger_page_notifications_ready",status="OBSERVED",detail="Bandeja de la fanpage verificada sin aviso de notificaciones desactivadas. Entrega de un mensaje nuevo pendiente de comprobar."));g.trace("messenger_page_notifications_ready")}
        return "{\"page_inbox_notifications_ready\":$ready,\"delivery_confirmed\":false}"
    }
}
