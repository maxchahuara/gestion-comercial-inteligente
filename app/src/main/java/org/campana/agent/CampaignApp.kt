package org.campana.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.campana.agent.agent.AgentEngine
import org.campana.agent.auth.*
import org.campana.agent.data.*
import org.campana.agent.jobs.TaskWorker
import org.campana.agent.net.Http
import org.campana.agent.net.safeError
import org.campana.agent.phone.IncomingMessage
import org.campana.agent.phone.IncomingRouting
import org.campana.agent.phone.PhoneAccessibilityService

class CampaignApp : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("agent","Actividad del agente",NotificationManager.IMPORTANCE_LOW))
        graph=AppGraph(this)
    }
}

class AppGraph(val context: Context,val db:CampaignDatabase=Room.databaseBuilder(context,CampaignDatabase::class.java,"campaigns.db").build()) {
    private val traceLines=java.util.ArrayDeque<String>()
    @Synchronized fun trace(message:String) {
        traceLines.addLast(System.currentTimeMillis().toString()+" "+message.take(600))
        while(traceLines.size>32)traceLines.removeFirst()
        runtime.audit(message)
    }
    @Synchronized fun runtimeSnapshot():String="paused=${paused.value} busy=${busy.value} visual=${visualWorking.value} model=${model.value}\n"+runtime.snapshot()+"\n"+traceLines.joinToString("\n")
    val status=MutableStateFlow("")
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default+CoroutineExceptionHandler { _,error->status.value=safeError(error) })
    val dao=db.dao()
    val prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val paused=MutableStateFlow(prefs.getBoolean("paused",false))
    val busy=MutableStateFlow(false)
    val visualWorking=MutableStateFlow(false)
    val incomingWake=MutableStateFlow(prefs.getBoolean("incoming_wakeup",true))
    val activeCampaign=MutableStateFlow(prefs.getString("campaign","") ?: "")
    val model=MutableStateFlow(prefs.getString("model","") ?: "")
    val models=MutableStateFlow<List<Pair<String,String>>>(emptyList())
    val http=Http()
    val auth=ChatGptAuth(context,SessionVault(context),http)
    internal val codexImages = org.campana.agent.imagecodex.CodexImageEngine(context)
    val assets=AssetRepository(context,dao)
    val conversations=org.campana.agent.agent.CampaignConversation(this)
    val postLinks=org.campana.agent.agent.PostLinks(this)
    val visualIncoming=org.campana.agent.phone.IncomingVisualReplies(this)
    val messengerAccounts=org.campana.agent.phone.MessengerAccountInspection(this)
    val inbox=org.campana.agent.phone.InboxScanner(this)
    val voices=org.campana.agent.audio.IncomingVoice(this)
    val voiceExport=org.campana.agent.audio.VoiceExport(this)
    val incomingIdentities=org.campana.agent.agent.IncomingIdentities(this)
    val engine=AgentEngine(this)
    val runtime=org.campana.agent.agent.AgentRuntime(this)
    val ready=CompletableDeferred<Unit>()
    init {
        scope.launch {
            try { dao.recoverInterrupted();conversations.migrateDedicatedScope();TaskWorker.scheduleSweep(context);ready.complete(Unit) }
            catch(e:Exception) { ready.completeExceptionally(e); status.value="No se pudo abrir la memoria local.";return@launch }
            runCatching {
                dao.campaigns().filter(org.campana.agent.agent.GatunaGuide::applies).forEach {
                    org.campana.agent.agent.GatunaSupport.prepareTopicPosts(this@AppGraph, it)
                }
            }.onFailure { trace("gatuna_topics_unavailable " + it.javaClass.simpleName) }
            runtime.ensureStarted("application_ready")
            messengerAccounts.ensure()
            inbox.ensureObservation()
            runCatching {
                val c=dao.campaign(activeCampaign.value)
                trace("incoming_scope="+if(c?.isDedicatedWhatsApp()==true)"WHATSAPP_ANY_DIRECT" else "CONFIGURED_RULES")
                val previous=dao.tasks(activeCampaign.value).firstOrNull{it.kind=="READ_POST"}
                if(previous!=null) {
                    trace("previous READ_POST status="+previous.status+" detail="+previous.detail.take(250))
                    dao.allReceipts(previous.campaignId).filter{it.taskId==previous.id && it.action.endsWith("screen_action")}.takeLast(5).forEach { r->
                        runCatching {
                            val d=org.campana.agent.net.json.parseToJsonElement(r.detail).jsonObject
                            val a=org.campana.agent.net.json.parseToJsonElement(d.getValue("arguments").jsonPrimitive.content).jsonObject
                            trace("previous screen action="+a["action"]+" node="+a["node_id"]+" result="+d.getValue("result").jsonPrimitive.content.take(300))
                        }
                    }
                }
                postLinks.ensurePreparation()
                incomingIdentities.resumePending()
                runtime.ensureStarted("application_ready")
            }.onFailure{status.value=safeError(it);trace("preparation_error "+safeError(it))}
        }
    }
    fun selectCampaign(id:String) { activeCampaign.value=id; prefs.edit().putString("campaign",id).apply() }
    fun selectModel(id:String) { model.value=id; prefs.edit().putString("model",id).apply() }
    fun setIncomingWake(value:Boolean) {
        incomingWake.value=value;prefs.edit().putBoolean("incoming_wakeup",value).apply()
        if(value)org.campana.agent.phone.SocialNotificationService.instance?.reconsiderIncoming()
    }
    fun pause(value:Boolean) {
        paused.value=value;prefs.edit().putBoolean("paused",value).apply()
        if(value){runtime.paused("owner");PhoneAccessibilityService.instance?.stopControl();postLinks.clear();engine.cancel()} else {runtime.resumed();scope.launch{messengerAccounts.ensure();postLinks.ensurePreparation();org.campana.agent.phone.SocialNotificationService.instance?.reconsiderIncoming()};TaskWorker.kick(context)}
    }
    fun resumeByOwner(){if(android.os.SystemClock.uptimeMillis()-runtime.recoveredAt>=700)pause(false)}
    suspend fun refreshModels() {
        val available=auth.models(); models.value=available
        prefs.edit().apply { available.forEach { (id,title)->putString("model_title_"+id,title) } }.apply()
        if (model.value !in available.map { it.first }) selectModel(available.firstOrNull()?.first ?: "")
    }
    suspend fun enqueue(task:AgentTask) {
        checkNotNull(dao.campaign(task.campaignId))
        if (dao.insert(task)!=-1L && task.status=="QUEUED") {TaskWorker.schedule(context,task);runtime.dispatch(task.id)}
    }
    suspend fun incoming(raw: IncomingMessage) {
        ready.await()
        if(!incomingWake.value || paused.value)return
        val message=incomingIdentities.enrich(raw)
        val campaigns=dao.campaigns()
        val c=IncomingRouting.campaign(campaigns,message,paused.value,prefs.getLong("reply_listen_since",0),System.currentTimeMillis()) ?: run{trace("incoming_not_routed paused="+paused.value+" title_match="+campaigns.any{it.allowsIncomingRecipient(message.title)}+" phone_match="+campaigns.any{message.senderPhone.isNotBlank() && it.allowsIncomingRecipient(message.senderPhone)});incomingIdentities.request(message,campaigns);return}
        if(message.channel==org.campana.agent.data.Channel.MESSENGER && message.matchesCampaignAccount(c))messengerAccounts.verifiedNativeAccount.value=true
        if(!message.matchesCampaignAccount(c)){status.value="Messenger: falta comprobar que el mensaje pertenece a la fanpage de la campaña. No se responderá desde la cuenta personal.";trace("incoming_account_not_verified channel="+message.channel);return}
        if(!postLinks.readyFor(c)){status.value="Preparando el enlace y el guion antes de atender mensajes.";return}
        val old=message.legacyFingerprint.takeIf{it.isNotBlank()}?.let{dao.taskForDedupe(it)}
        if(old!=null && old.campaignId==c.id && old.notificationKey==message.key && old.kind=="REPLY" && old.instruction==message.text && old.status in setOf("SUBMITTING","SUBMITTED","UNCERTAIN")) {
            trace("incoming_already_handled legacy=true");return
        }
        val pending=dao.taskForDedupe(message.fingerprint)
        val needsVoiceFile=message.voiceNote && !voices.canRead(message)
        val visualMessage=if(needsVoiceFile)message.copy(audioUri="") else message
        val needsVisual=needsVoiceFile || withContext(Dispatchers.Main){org.campana.agent.phone.SocialNotificationService.instance?.canReply(message.key,message.fingerprint)!=true}
        if(pending!=null) {
            if(needsVisual && pending.status in setOf("WAITING_OWNER","WAITING_PERMISSION","WAITING_UNLOCK","WAITING_NETWORK") && dao.allReceipts(c.id).none{it.taskId==pending.id && it.action=="external_submit"})visualIncoming.record(pending,visualMessage)
            if(pending.kind=="REPLY" && pending.campaignId==c.id && pending.notificationKey==message.key && pending.status in setOf("WAITING_OWNER","WAITING_PERMISSION","WAITING_UNLOCK","WAITING_NETWORK") && dao.allReceipts(c.id).none{it.taskId==pending.id && it.action=="external_submit"} && dao.queueReviewed(pending.id,System.currentTimeMillis())==1){TaskWorker.schedule(context,pending.copy(status="QUEUED"),reviewed=true);runtime.dispatch(pending.id)}
            return
        }
        val task=AgentTask(campaignId=c.id,channel=message.channel.name,kind="REPLY",
            instruction=message.text,recipient=checkNotNull(message.recipientFor(c)),dedupeKey=message.fingerprint,
            notificationKey=message.key,notificationFingerprint=message.fingerprint,
            status="QUEUED")
        if(needsVisual){val inserted=db.withTransaction{if(dao.insert(task)==-1L)false else {visualIncoming.record(task,visualMessage);true}};if(inserted){TaskWorker.schedule(context,task);runtime.dispatch(task.id)}} else enqueue(task)
        trace("incoming_queued channel="+message.channel)
    }
}
