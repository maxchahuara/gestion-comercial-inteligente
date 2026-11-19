package org.campana.agent.agent

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.campana.agent.AppGraph
import org.campana.agent.R
import org.campana.agent.data.*
import org.campana.agent.net.*
import org.campana.agent.phone.*

@Serializable data class PublishedPostTarget(val parentId:String,val account:String,val caption:String)

class PostLinks(private val g:AppGraph) {
    private val preparationMutex=kotlinx.coroutines.sync.Mutex()
    val ready=MutableStateFlow(false)
    private data class Pending(val taskId:String,val campaignId:String,val target:PublishedPostTarget,val control:Any,val until:Long,var selected:Boolean=false)
    private var pending:Pending?=null
    private val receiver=ComponentName(g.context,PostLinkReceiverActivity::class.java)
    init { enableReceiver(false) }
    private fun enableReceiver(enable:Boolean){g.context.packageManager.setComponentEnabledSetting(receiver,if(enable)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)}
    fun clear(){pending=null;enableReceiver(false)}
    fun sharing():Boolean=pending?.let{SystemClock.uptimeMillis()<=it.until}==true
    // An expired system chooser remains readable/cancellable, but cannot authorize a share target.
    fun extraSurface(pkg:String)=PostLinkRules.readableSurface(pkg,g.context.packageName,sharing())
    suspend fun target(task:AgentTask):PublishedPostTarget {
        require(task.kind=="READ_POST" && task.channel=="FACEBOOK")
        val t=json.decodeFromString<PublishedPostTarget>(task.instruction)
        val parent=checkNotNull(g.dao.task(t.parentId))
        require(parent.campaignId==task.campaignId && parent.channel=="FACEBOOK" && parent.kind=="POST" && parent.status=="SUBMITTED")
        val receipts=g.dao.allReceipts(task.campaignId)
        require(receipts.count{it.taskId==parent.id && it.action=="external_submit" && it.detail=="Destino verificado: "+t.account}==1)
        require(caption(receipts,parent.id)==t.caption && t.caption.isNotBlank())
        return t
    }
    private fun caption(receipts:List<Receipt>,parent:String):String? = receipts.asReversed().firstNotNullOfOrNull { r->
        if(r.taskId!=parent || r.action!="share_attachment")null else runCatching {
            val d=json.parseToJsonElement(r.detail).jsonObject
            val result=d.str("result");if(result.contains("\"error\""))null else json.parseToJsonElement(d.str("arguments")).jsonObject.str("caption").takeIf{it.isNotBlank()}
        }.getOrNull()
    }
    suspend fun links(campaignId:String):List<String> = g.dao.allReceipts(campaignId).filter{it.action=="post_link_verified" && it.status=="OBSERVED"}.mapNotNull {
        runCatching{PostLinkRules.facebookUrl(json.parseToJsonElement(it.detail).jsonObject.str("url"))}.getOrNull()
    }.distinct()
    internal suspend fun gatunaLinks(c: Campaign): Map<GatunaTopic, String> {
        if (!GatunaGuide.applies(c) || c.accountIdentity.isBlank()) return emptyMap()
        val parentTasks = g.dao.tasks(c.id).filter {
            it.kind == "POST" && it.channel == Channel.FACEBOOK.name && it.status == "SUBMITTED" &&
                sameContact(it.recipient, c.accountIdentity)
        }.associateBy { it.id }
        val topicsByKey = GatunaTopic.entries.associateBy { it.draftKey(c.id) }
        return g.dao.recentReceipts(c.id, "post_link_verified").asReversed().mapNotNull { receipt ->
            if (receipt.status != "OBSERVED") return@mapNotNull null
            val detail = runCatching { json.parseToJsonElement(receipt.detail).jsonObject }.getOrNull() ?: return@mapNotNull null
            val parent = parentTasks[detail.str("parent_task_id")] ?: return@mapNotNull null
            val topic = topicsByKey[parent.dedupeKey] ?: return@mapNotNull null
            if (!sameContact(detail.str("account"), parent.recipient)) return@mapNotNull null
            val url = PostLinkRules.facebookUrl(detail.str("url")) ?: return@mapNotNull null
            topic to url
        }.toMap()
    }

    suspend fun needsPreparation(c:Campaign):Boolean {
        if(c.mode!="REACTIVE" || !c.autoReply)return false
        return g.dao.tasks(c.id).any{it.channel=="FACEBOOK" && it.kind=="POST" && it.status=="SUBMITTED"}
    }
    suspend fun readyFor(c:Campaign):Boolean {
        if(!needsPreparation(c) || GatunaGuide.applies(c))return true
        return links(c.id).isNotEmpty() && g.dao.allReceipts(c.id).any{it.action=="reply_rehearsal" && it.status=="PASSED" && runCatching{val d=json.parseToJsonElement(it.detail).jsonObject;d["context_updated_at"]?.jsonPrimitive?.long==c.updatedAt && d["policy_version"]?.jsonPrimitive?.int==CampaignReplyPolicy.VERSION}.getOrDefault(false)}
    }
    suspend fun ensurePreparation() {
        preparationMutex.withLock{prepare()}
    }
    private suspend fun prepare() {
        g.ready.await()
        val c=g.dao.campaign(g.activeCampaign.value) ?: return
        ready.value=readyFor(c)
        if (GatunaGuide.applies(c)) {
            if (!g.paused.value && c.mode in setOf("AUTO", "REACTIVE") && c.accountIdentity.isNotBlank()) {
                val missing = GatunaTopic.entries.toSet() - gatunaLinks(c).keys
                val keys = missing.map { it.draftKey(c.id) }.toSet()
                val receipts = g.dao.allReceipts(c.id)
                val tasks = g.dao.tasks(c.id)
                val parent = tasks.firstOrNull {
                    it.dedupeKey in keys && it.kind == "POST" && it.status == "SUBMITTED" && it.channel == "FACEBOOK" &&
                        sameContact(it.recipient, c.accountIdentity) &&
                        receipts.count { r -> r.taskId == it.id && r.action == "external_submit" && r.detail == "Destino verificado: " + it.recipient } == 1 &&
                        caption(receipts, it.id) != null && tasks.none { read -> read.dedupeKey == "gatuna-post-link:" + it.id }
                }
                if (parent != null) enqueueRead(parent, parent.recipient, checkNotNull(caption(receipts, parent.id)), "gatuna-post-link:" + parent.id)
            }
            return
        }
        if(g.paused.value || ready.value || !needsPreparation(c))return
        val available=g.auth.models()
        val family=available.filter{Regex("^gpt-6\\.1(?:-|$)").containsMatchIn(it.first)}
        val model=family.firstOrNull{it.first==g.model.value} ?: family.firstOrNull{it.first=="gpt-6.1-terra"} ?: family.firstOrNull()
        if(model==null){g.status.value="La cuenta no ofrece GPT-6.1. Selecciona una cuenta con acceso para preparar la atención.";return}
        g.models.value=available;g.selectModel(model.first)
        if(links(c.id).isNotEmpty()) {
            g.trace("reply_rehearsal_started existing_link=true")
            ReplyDrafting.rehearse(g,c)
            ready.value=true
            g.trace("reply_rehearsal_passed sent_messages=0 ready=true")
            org.campana.agent.phone.SocialNotificationService.instance?.reconsiderIncoming()
            return
        }
        val receipts=g.dao.allReceipts(c.id)
        val parent=g.dao.tasks(c.id).firstOrNull{it.kind=="POST" && it.channel=="FACEBOOK" && it.status=="SUBMITTED" && caption(receipts,it.id)!=null} ?: return
        val actor=receipts.singleOrNull{it.taskId==parent.id && it.action=="external_submit"}?.detail?.removePrefix("Destino verificado: ")?.takeIf{it==c.accountIdentity} ?: return
        val key="post-link:"+parent.id+":"+c.updatedAt
        val existing=g.dao.tasks(c.id).firstOrNull{it.dedupeKey==key}
        if(existing!=null) {
            if(existing.status in setOf("WAITING_OWNER","WAITING_PERMISSION","WAITING_UNLOCK","DRAFT") && g.dao.queueReviewed(existing.id,System.currentTimeMillis())==1)
                org.campana.agent.jobs.TaskWorker.schedule(g.context,existing.copy(status="QUEUED"),reviewed=true)
            return
        }
        enqueueRead(parent, actor, checkNotNull(caption(receipts, parent.id)), key)
        g.status.value="El agente está recuperando el enlace y ensayando la conversación antes de atender."
    }

    private suspend fun enqueueRead(parent: AgentTask, actor: String, caption: String, key: String) {
        val value = PublishedPostTarget(parent.id, actor, caption)
        g.enqueue(AgentTask(campaignId = parent.campaignId, channel = "FACEBOOK", kind = "READ_POST",
            recipient = actor, instruction = json.encodeToString(value), dedupeKey = key))
    }
    suspend fun act(task:AgentTask,service:PhoneAccessibilityService,state:ScreenState,node:ScreenNode,action:String):Boolean {
        val target=target(task)
        check(!g.paused.value);require(action in setOf("click","scroll","scroll_back"))
        g.trace("read_action action="+action+" observed_node="+node.id+" scrollable="+node.scrollable)
        return withContext(Dispatchers.Main) {
            service.requireControl()
            val fresh=service.observe()
            require(fresh.packageName==state.packageName && fresh.windowId==state.windowId)
            val n=PostLinkRules.liveNode(fresh.nodes,node)
                ?: error("El elemento cambió; observa otra vez.")
            val label=n.text.ifBlank{n.description}
            val appLabel = g.context.getString(R.string.app_name)
            val ownShareTarget = PostLinkRules.chooserPackage(fresh.packageName) && PostLinkRules.ownShareTarget(label, appLabel)
            if(action in setOf("scroll","scroll_back"))require(n.scrollable && (fresh.packageName=="com.facebook.katana" || extraSurface(fresh.packageName)))
            else if(fresh.packageName=="com.facebook.katana" && PostLinkRules.feedShare(n.description)) {
                val actors=fresh.nodes.map{ActorEvidence(it.text,it.description,it.editable,it.left,it.top,it.right,it.bottom,it.enabled,it.showingHint)}
                val share=ActorEvidence(n.text,n.description,n.editable,n.left,n.top,n.right,n.bottom)
                check(PostLinkRules.verifiedPost(actors,target.account,target.caption,share)){"Primero muestra el autor, la descripción completa, la foto y Compartir de la misma publicación."}
                clear();pending=Pending(task.id,task.campaignId,target,service.controlToken(),SystemClock.uptimeMillis()+120_000)
                enableReceiver(true)
            } else {
                check(PostLinkRules.navigation(fresh.packageName,label,sharing(),appLabel)){"La inspección solo admite navegación de lectura y compartir hacia $appLabel. No permite enviar, publicar, copiar ni editar."}
                if(ownShareTarget) {
                    val matches=g.context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"),PackageManager.MATCH_DEFAULT_ONLY)
                        .filter{normalized(it.loadLabel(g.context.packageManager).toString())==normalized(appLabel)}
                    check(matches.size==1 && matches.single().activityInfo.packageName==g.context.packageName && matches.single().activityInfo.name==receiver.className){"Destino de compartir ambiguo."}
                }
            }
            service.uncoverTarget(Rect(n.left,n.top,n.right,n.bottom))
            val performed=service.act(fresh.id,n.id,action,"")
            if(performed && ownShareTarget)checkNotNull(pending).selected=true
            performed
        }
    }
    suspend fun receive(intent:Intent):Boolean {
        val p=withContext(Dispatchers.Main) {
            val value=pending ?: return@withContext null
            val service=PhoneAccessibilityService.instance ?: return@withContext null
            if(!sharing() || !value.selected || !service.sameControl(value.control) || g.paused.value)return@withContext null
            if(intent.action!=Intent.ACTION_SEND || intent.type!="text/plain" || intent.hasExtra(Intent.EXTRA_INTENT) || intent.hasExtra(Intent.EXTRA_STREAM))return@withContext null
            value
        } ?: return false
        val url=PostLinkRules.facebookUrl(runCatching{intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()}.getOrDefault("")) ?: return false
        val task=checkNotNull(g.dao.task(p.taskId));check(target(task)==p.target)
        withContext(Dispatchers.Main){check(PhoneAccessibilityService.instance?.sameControl(p.control)==true && !g.paused.value)}
        g.dao.insert(Receipt(campaignId=p.campaignId,taskId=p.taskId,action="post_link_verified",status="OBSERVED",detail=buildJsonObject {
            put("parent_task_id",p.target.parentId);put("account",p.target.account);put("url",url)
        }.toString()))
        withContext(Dispatchers.Main){if(pending===p)clear()}
        g.trace("post_link_verified")
        return true
    }
    suspend fun received(taskId:String):String=g.dao.allReceipts(checkNotNull(g.dao.task(taskId)).campaignId).lastOrNull{it.taskId==taskId && it.action=="post_link_verified"}?.detail ?: "Todavía no se recibió una URL en la app. Continúa observando la pantalla y decide cómo completar el objetivo mediante las herramientas de lectura y el receptor nativo."
}
