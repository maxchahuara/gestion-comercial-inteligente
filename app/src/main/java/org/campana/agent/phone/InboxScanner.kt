package org.campana.agent.phone

import android.graphics.Rect
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import androidx.room.withTransaction
import org.campana.agent.AppGraph
import org.campana.agent.agent.MessengerAccountRules
import org.campana.agent.agent.PostLinkRules
import org.campana.agent.agent.MessengerInboxRules
import org.campana.agent.agent.InboxMessageEvidence
import org.campana.agent.agent.MessengerInboxNavigation as Nav
import org.campana.agent.data.*
import org.campana.agent.net.*

/** Native Page inbox observations cover direct messages that produce no Android notification. */
class InboxScanner(private val g:AppGraph) {
    @Volatile var diagnostic="{}";private set
    @Volatile var threadDiagnostic="{}";private set
    private val openedRows=java.util.concurrent.ConcurrentHashMap<String,ScreenNode>()
    private val pageLeases=java.util.concurrent.ConcurrentHashMap<String,Any>()
    @Volatile private var nextScanAt=0L
    private var seenScope=""
    private val seenRows=linkedSetOf<String>()
    fun begin(id:String){openedRows.remove(id);pageLeases.remove(id)}
    fun markPageVerified(id:String,token:Any){pageLeases[id]=token}
    private suspend fun hasPageLease(id:String)=withContext(Dispatchers.Main){pageLeases[id]?.let{PhoneAccessibilityService.instance?.sameControl(it)}==true}
    fun remember(state:ScreenState){if(state.packageName==Channel.MESSENGER.packageName)diagnostic=json.encodeToString(state)}
    suspend fun ensureObservation() {
        if(g.paused.value || !g.incomingWake.value || g.busy.value || g.visualWorking.value || System.currentTimeMillis()<nextScanAt)return
        val c=g.dao.campaign(g.activeCampaign.value) ?: return
        if(!c.autoReply || c.mode!="REACTIVE" || Channel.MESSENGER !in c.channelList() || !g.messengerAccounts.sessionVerified(c))return
        val key="inbox-scan-v030:"+c.id+":"+c.updatedAt+":"+g.runtime.gate.epoch
        val existing=g.dao.taskForDedupe(key)
        if(existing!=null){if(g.dao.queueInboxScan(existing.id,System.currentTimeMillis())==1){nextScanAt=System.currentTimeMillis()+30_000;g.runtime.dispatch(existing.id)};return}
        nextScanAt=System.currentTimeMillis()+30_000
        g.enqueue(AgentTask(campaignId=c.id,channel="MESSENGER",kind="READ_INBOX",recipient=c.accountIdentity,dedupeKey=key,
            instruction="Atiende mensajes pendientes de la fanpage «${c.accountIdentity}», de cualquier remitente. Primero muestra su Menú y llama verify_messaging_account para comprobar de nuevo la página en esta operación; selecciona únicamente esa página si hace falta. Vuelve a su bandeja y revisa No leídos. Si queda vacío porque el agente ya abrió un mensaje, consulta Todo y la conversación reciente cuyo último mensaje provenga del visitante y todavía no tenga respuesta. No dependas de No respondidos: puede estar vacío pese a haber entradas reales. No abras un chat cuyo último mensaje diga Tú: ni conversaciones antiguas de otro año. Decide la navegación desde cada imagen y los elementos nativos, sin instrucciones de una computadora. Abre una conversación pendiente y llama inspect_inbox_thread: el código leerá remitente, texto, fecha y perfil empresarial y encolará una respuesta separada con el generador de campaña. Esta revisión no redacta ni envía directamente. No abras bandejas personales, no llames ni cambies ajustes."))
    }
    suspend fun act(task:AgentTask,service:PhoneAccessibilityService,state:ScreenState,node:ScreenNode,action:String):Boolean {
        check(task.kind=="READ_INBOX");g.engine.authorized(task)
        val c=checkNotNull(g.dao.campaign(task.campaignId));check(g.messengerAccounts.sessionVerified(c))
        require(action in setOf("click","scroll","scroll_back"))
        if(!hasPageLease(task.id))return g.messengerAccounts.act(task,service,state,node,action)
        var row=false
        if(action=="click") {
            val labels=listOf(node.text,node.description,node.nativeDescription).map{normalized(it)}
            val filter=labels.any{it in setOf("todo","no leidos","no respondidos","all","unread","unanswered") || it.startsWith("pestana \"no respondidos\"") || MessengerAccountRules.chatTab(it)}
            val tabs=state.nodes.filter{n->listOf(n.text,n.description).any{normalized(it)=="no respondidos"}}
            val edge=tabs.minByOrNull{it.bottom-it.top}?.bottom ?: Int.MAX_VALUE
            val width=state.nodes.maxOfOrNull{it.right} ?: 0
            val height=state.nodes.maxOfOrNull{it.bottom} ?: 0
            row=MessengerAccountRules.pageInbox(state.nodes) && node.top>=edge && node.bottom<height*.94 && node.right-node.left>width*.5 && node.bottom-node.top>=40*service.resources.displayMetrics.density
            if(!filter && !row)return g.messengerAccounts.act(task,service,state,node,action)
        } else check(node.scrollable)
        val result=withContext(Dispatchers.Main) {
            service.requireControl();val fresh=service.observe()
            check(fresh.packageName==Channel.MESSENGER.packageName && fresh.windowId==state.windowId)
            val live=PostLinkRules.liveNode(fresh.nodes,node) ?: error("La pantalla cambió.")
            service.uncoverTarget(Rect(live.left,live.top,live.right,live.bottom))
            service.act(fresh.id,live.id,action,"")
        }
        if(result && row)openedRows[task.id]=node
        return result
    }
    private data class Inspection(val detail:String,val queued:Boolean=false)
    suspend fun inspect(task:AgentTask):String=inspectNative(task).detail
    private suspend fun inspectNative(task:AgentTask):Inspection {
        check(task.kind=="READ_INBOX");g.engine.authorized(task)
        check(openedRows.containsKey(task.id)){"Primero abre una fila de la bandeja de la fanpage."}
        check(hasPageLease(task.id)){"Primero verifica la fanpage en el Menú de esta operación."}
        check(g.messengerAccounts.sessionVerified(checkNotNull(g.dao.campaign(task.campaignId))))
        val state=withContext(Dispatchers.Main){checkNotNull(PhoneAccessibilityService.instance).observe()}
        check(state.packageName==Channel.MESSENGER.packageName)
        diagnostic=json.encodeToString(state)
        if(org.campana.agent.BuildConfig.DEBUG)threadDiagnostic=diagnostic
        g.dao.insert(Receipt(campaignId=task.campaignId,taskId=task.id,action="inbox_thread_observed",status="OBSERVED",detail="Conversación abierta desde la bandeja verificada de la fanpage."))
        g.trace("messenger_inbox_thread_observed")
        val evidence=MessengerInboxRules.pending(state,System.currentTimeMillis(),requireBusinessTools=false) ?: return Inspection("No hay un último mensaje entrante verificable pendiente; no se encoló una respuesta.")
        val c=checkNotNull(g.dao.campaign(task.campaignId))
        if(!g.postLinks.readyFor(c))return Inspection("Preparando el contexto y el enlace antes de responder.")
        if(evidence.receivedAt<c.updatedAt)return Inspection("El mensaje es anterior al contexto actual de campaña.")
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(listOf(c.id,c.accountIdentity,evidence.sender,evidence.receivedAt.toString(),evidence.nativeMessage,evidence.previousOwn).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
        val key="ui-inbox:"+fingerprint
        val previous=g.dao.taskForDedupe(key)
        if(previous!=null && (previous.status in setOf("SUBMITTED","SUBMITTING","UNCERTAIN","CANCELLED") || g.dao.hasReceipt(previous.id,"external_submit")))return Inspection("Este mensaje ya tiene un intento de respuesta; no se duplicará.")
        val reply=previous ?: AgentTask(campaignId=c.id,channel="MESSENGER",kind="REPLY",recipient=evidence.sender,instruction=evidence.message,dedupeKey=key,notificationKey=key,notificationFingerprint=fingerprint)
        val controlId=withContext(Dispatchers.Main){checkNotNull(PhoneAccessibilityService.instance).controlIdentity()}
        val detail=buildJsonObject {put("parent",task.id);put("account",c.accountIdentity);put("epoch",g.runtime.gate.epoch);put("context_updated_at",c.updatedAt);put("captured_at",System.currentTimeMillis());put("fingerprint",fingerprint);put("controller_instance",controlId.first);put("controller_serial",controlId.second);put("evidence",json.encodeToJsonElement(evidence))}.toString()
        val queued=g.db.withTransaction {
            if(previous==null && g.dao.insert(reply)==-1L)false else {
                g.dao.insert(Receipt(campaignId=c.id,taskId=reply.id,action="inbox_reply_source",status="OBSERVED",detail=detail))
                if(previous!=null && previous.status!="QUEUED")g.dao.queueReviewed(previous.id,System.currentTimeMillis())
                true
            }
        }
        if(queued){g.trace("incoming_detected channel=MESSENGER origin=native_inbox");org.campana.agent.jobs.TaskWorker.schedule(g.context,reply);g.runtime.dispatch(reply.id);g.trace("messenger_inbox_reply_queued")}
        return Inspection("Mensaje entrante real identificado y respuesta de campaña encolada. No se inició una conversación nueva.",queued)
    }
    /** Runs inside this phone's control session. No coordinates, host input, or sending. */
    suspend fun nativeScan(task:AgentTask,service:PhoneAccessibilityService):String? {
        check(task.kind=="READ_INBOX");g.engine.authorized(task)
        val c=checkNotNull(g.dao.campaign(task.campaignId))
        val scope=c.id+":"+c.updatedAt+":"+g.runtime.gate.epoch
        if(seenScope!=scope){seenRows.clear();seenScope=scope}
        suspend fun state()=withContext(Dispatchers.Main){service.observe().also{service.requireControl();remember(it)}}
        suspend fun click(s:ScreenState,n:ScreenNode){check(act(task,service,s,n,"click"));delay(450)}
        suspend fun back(){check(back(task,service));delay(450)}
        try {
            // Re-prove the Page at every poll, including after another app or process restart.
            var verified=false
            for(step in 0 until 9) {
                val s=state();if(s.packageName!=Channel.MESSENGER.packageName)return null
                if(MessengerAccountRules.activePageMenu(s.nodes,c.accountIdentity)) {
                    g.messengerAccounts.verifyCurrent(task);verified=hasPageLease(task.id);break
                }
                val target=when {
                    Nav.menu(s.nodes)->s.nodes.firstOrNull{it.enabled && it.clickable && Nav.labels(it).any{v->v.contains("cambiar de perfil") || v.contains("switch profile")}}
                    s.nodes.any{Nav.labels(it).any(MessengerAccountRules::selectorLabel)}->s.nodes.firstOrNull{it.enabled && it.clickable && Nav.labels(it).any{v->MessengerAccountRules.pageChoice(v,c.accountIdentity)}}
                    else->s.nodes.firstOrNull{it.enabled && it.clickable && Nav.labels(it).any(MessengerAccountRules::menuTab)}
                }
                if(target!=null)click(s,target)
                else if(MessengerInboxRules.header(s.nodes)!=null)back() else return null
            }
            if(!verified)return null
            var s=state()
            val chats=s.nodes.firstOrNull{it.enabled && it.clickable && Nav.labels(it).any(MessengerAccountRules::chatTab)} ?: return null
            click(s,chats);s=state()
            if(!MessengerAccountRules.pageInbox(s.nodes))return null
            var inspected=0
            for(filter in listOf("No leídos","Todo")) {
                s=state();val tab=Nav.exact(s.nodes,filter) ?: return null
                if(!tab.selected){click(s,tab);s=state()}
                if(!MessengerAccountRules.pageInbox(s.nodes))return null
                while(inspected<4) {
                    val row=Nav.rows(s).firstOrNull{r->!Nav.previousYear(s,r,java.time.Year.now().value) && !Nav.ownPreview(s,r) && Nav.signature(s,r) !in seenRows} ?: break
                    val signature=Nav.signature(s,row)
                    click(s,row)
                    val outcome=inspectNative(task);inspected++
                    if(outcome.queued){g.trace("messenger_native_poll queued=true inspected="+inspected);return outcome.detail}
                    seenRows.add(signature);if(seenRows.size>256)seenRows.remove(seenRows.first())
                    back();s=state()
                    if(!MessengerAccountRules.pageInbox(s.nodes))return null
                }
            }
            g.trace("messenger_native_poll queued=false inspected="+inspected)
            return "Bandeja de la fanpage verificada; revisión nativa terminada sin nuevos envíos."
        } catch(e:CancellationException){throw e} catch(e:Exception) {
            g.trace("messenger_native_poll_visual_fallback type="+e.javaClass.simpleName)
            return null // The visual agent handles unfamiliar layouts under the same guards.
        }
    }
    suspend fun sourceData(task:AgentTask):InboxMessageEvidence? {
        if(task.kind!="REPLY" || !task.notificationKey.startsWith("ui-inbox:"))return null
        val receipt=g.dao.latestReceiptForTask(task.id,"inbox_reply_source")?.takeIf{it.status=="OBSERVED"} ?: error("Falta la evidencia nativa de bandeja.")
        val d=json.parseToJsonElement(receipt.detail).jsonObject;val c=checkNotNull(g.dao.campaign(task.campaignId))
        check(g.runtime.gate.permits(d["epoch"]?.jsonPrimitive?.longOrNull) && !g.paused.value)
        check(d.str("account")==c.accountIdentity && d.number("context_updated_at")==c.updatedAt && d.str("fingerprint")==task.notificationFingerprint)
        check(System.currentTimeMillis()-d.number("captured_at") in 0..300_000)
        val parent=checkNotNull(g.dao.task(d.str("parent")))
        check(parent.kind=="READ_INBOX" && parent.campaignId==c.id && parent.recipient==c.accountIdentity)
        check(g.dao.hasReceipt(parent.id,"inbox_thread_observed"))
        return json.decodeFromJsonElement<InboxMessageEvidence>(d.getValue("evidence")).also{check(it.sender==task.recipient && it.message==task.instruction && it.receivedAt>=c.updatedAt)}
    }
    suspend fun source(task:AgentTask):IncomingMessage?=sourceData(task)?.let{IncomingMessage(task.notificationKey,task.notificationFingerprint,Channel.MESSENGER,it.sender,it.message,it.receivedAt,direct=true,nativeAccount=checkNotNull(g.dao.campaign(task.campaignId)).accountIdentity)}
    fun finish(id:String){openedRows.remove(id);pageLeases.remove(id)}
    suspend fun requirePending(task:AgentTask,initial:Boolean=false) {
        val fixed=checkNotNull(sourceData(task))
        val record=json.parseToJsonElement(checkNotNull(g.dao.latestReceiptForTask(task.id,"inbox_reply_source")).detail).jsonObject
        withContext(Dispatchers.Main){val id=checkNotNull(PhoneAccessibilityService.instance).controlIdentity();check(id.first==record.str("controller_instance") && id.second==record.number("controller_serial")+1){"Otra operación tomó la pantalla; se volverá a observar la conversación antes de responder."}}
        val current=withContext(Dispatchers.Main){val service=checkNotNull(PhoneAccessibilityService.instance);service.requireControl();MessengerInboxRules.pending(service.peek(),System.currentTimeMillis(),requireBusinessTools=initial)}
            ?: error("El último mensaje ya no es una entrada pendiente verificable.")
        check(current.windowId==fixed.windowId && current.sender==fixed.sender && current.nativeMessage==fixed.nativeMessage && current.receivedAt==fixed.receivedAt){"La conversación o su último mensaje cambió; se revisará de nuevo la bandeja."}
    }
    suspend fun open(task:AgentTask){requirePending(task)}
    suspend fun savedDraft(task:AgentTask):String? {
        if(!task.notificationKey.startsWith("ui-inbox:"))return null
        val c=checkNotNull(g.dao.campaign(task.campaignId))
        val saved=g.dao.latestReceiptForTask(task.id,"reply_draft")?.let{runCatching{json.parseToJsonElement(it.detail).jsonObject}.getOrNull()}
        if(saved!=null && saved.number("epoch")==g.runtime.gate.epoch && saved.number("context_updated_at")==c.updatedAt && saved.str("fingerprint")==task.notificationFingerprint)return saved.str("text").takeIf{it.isNotBlank()}
        return successfulDraft(task)
    }
    private suspend fun successfulDraft(task:AgentTask):String? {
        val c=checkNotNull(g.dao.campaign(task.campaignId));val records=g.dao.receiptsForTask(task.id)
        return records.asReversed().firstNotNullOfOrNull { r->
            if(!r.action.endsWith("screen_action"))return@firstNotNullOfOrNull null
            runCatching {
                val d=json.parseToJsonElement(r.detail).jsonObject;val args=json.parseToJsonElement(d.str("arguments")).jsonObject
                val result=json.parseToJsonElement(d.str("result")).jsonObject
                val source=records.lastOrNull{it.action=="inbox_reply_source" && it.createdAt<=r.createdAt}?.let{json.parseToJsonElement(it.detail).jsonObject}
                if(args.str("action")=="write" && result["performed"]?.jsonPrimitive?.booleanOrNull==true && source?.number("epoch")==g.runtime.gate.epoch && source.number("context_updated_at")==c.updatedAt && source.str("fingerprint")==task.notificationFingerprint)args.str("text").takeIf{it.isNotBlank()} else null
            }.getOrNull()
        }
    }
    suspend fun saveDraft(task:AgentTask,text:String) {
        if(!task.notificationKey.startsWith("ui-inbox:"))return
        val c=checkNotNull(g.dao.campaign(task.campaignId))
        g.dao.insert(Receipt(campaignId=c.id,taskId=task.id,action="reply_draft",status="PREPARED",detail=buildJsonObject{put("text",text);put("epoch",g.runtime.gate.epoch);put("context_updated_at",c.updatedAt);put("fingerprint",task.notificationFingerprint)}.toString()))
    }
    suspend fun ownsDraft(task:AgentTask,text:String)=task.notificationKey.startsWith("ui-inbox:") && successfulDraft(task)==text
    suspend fun back(task:AgentTask,service:PhoneAccessibilityService):Boolean=withContext(Dispatchers.Main) {
        val state=service.peek();check(!MessengerAccountRules.pageInbox(state.nodes)){"No se sale de la bandeja de la fanpage durante esta revisión."}
        openedRows.remove(task.id);service.navigateBack(Channel.MESSENGER.packageName)
    }
}
