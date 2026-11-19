package org.campana.agent.agent

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import org.campana.agent.AppGraph
import org.campana.agent.jobs.AgentRuntimeService
import org.campana.agent.phone.PhoneAccessibilityService
import org.campana.agent.phone.SocialNotificationService
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Entirely phone-owned supervisor. WorkManager remains a recovery path, not the message trigger. */
class AgentRuntime(private val g:AppGraph) {
    val gate=SessionGate(!g.paused.value,g.prefs.getLong("session_epoch",0))
    val running=MutableStateFlow(false)
    val phase=MutableStateFlow(if(g.paused.value)"Control humano" else "Preparando sesión permanente")
    val report=MutableStateFlow("")
    private val tasks=ConcurrentHashMap<String,Job>()
    private val epochs=ConcurrentHashMap<String,Long>()
    private var service:AgentRuntimeService?=null
    @Volatile var recoveredAt=0L;private set
    @Volatile var startedAt=SystemClock.uptimeMillis();private set
    private val auditQueue=Channel<String>(256,BufferOverflow.DROP_OLDEST)
    private var received=g.prefs.getLong("runtime_received",0)
    private var submitted=g.prefs.getLong("runtime_submitted",0)
    private var failed=g.prefs.getLong("runtime_failed",0)
    private var lastBeat=0L
    private val installedAt=g.context.packageManager.getPackageInfo(g.context.packageName,0).lastUpdateTime
    private var preparation:Job?=null
    private var idleSince=0L
    init {g.scope.launch(Dispatchers.IO){for(line in auditQueue)runCatching {
        val dir=File(g.context.filesDir,"runtime").apply{mkdirs()};val file=File(dir,"events.jsonl")
        if(file.length()>2_000_000){val old=File(dir,"events.previous.jsonl");old.delete();file.renameTo(old)}
        file.appendText(line+"\n")
    }}}
    @Synchronized fun audit(message:String) {
        if(message.startsWith("incoming_detected"))received++
        if(message.startsWith("incoming_reply_submitted"))submitted++
        if(message.startsWith("task_error"))failed++
        if(message.startsWith("incoming_") || message.startsWith("task_error"))g.prefs.edit().putLong("runtime_received",received).putLong("runtime_submitted",submitted).putLong("runtime_failed",failed).apply()
        auditQueue.trySend(org.campana.agent.net.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),kotlinx.serialization.json.buildJsonObject {
            put("at",kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()));put("event",kotlinx.serialization.json.JsonPrimitive(message.take(600)))
        }))
    }
    fun connected():Boolean=g.context.getSystemService(ConnectivityManager::class.java).activeNetwork?.let{
        g.context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }==true
    fun ensureStarted(reason:String) {
        if(g.paused.value || !g.prefs.getBoolean("ui_enabled",false))return
        if(running.value)return
        runCatching{ContextCompat.startForegroundService(g.context,Intent(g.context,AgentRuntimeService::class.java))}
            .onFailure{phase.value="Abre la app para iniciar el servicio permanente";g.trace("runtime_start_deferred reason="+reason+" type="+it.javaClass.simpleName)}
    }
    fun resumed(){if(!gate.active)startedAt=SystemClock.uptimeMillis();gate.resume();g.prefs.edit().putLong("session_epoch",gate.epoch).apply();ensureStarted("owner_resume")}
    fun paused(reason:String) {
        gate.recover();recoveredAt=SystemClock.uptimeMillis()
        g.prefs.edit().putLong("session_epoch",gate.epoch).putLong("soak_stopped_at",System.currentTimeMillis()).putString("soak_stop_reason",reason).apply()
        tasks.values.forEach{it.cancel()};phase.value="Control humano";service?.stopSelf();running.value=false
        g.trace("runtime_paused reason="+reason)
    }
    fun attach(value:AgentRuntimeService){service=value;running.value=true;g.trace("runtime_foreground_connected")}
    fun detach(value:AgentRuntimeService){if(service===value){service=null;running.value=false;if(!g.paused.value)phase.value="Recuperando servicio permanente"}}
    fun bindTask(id:String):Long=gate.epoch.also{epochs[id]=it}
    fun unbindTask(id:String){epochs.remove(id)}
    fun requireEpoch(epoch:Long){check(!g.paused.value && running.value && gate.permits(epoch)){"El propietario recuperó el control o cambió la sesión."}}
    fun requireTask(id:String){requireEpoch(checkNotNull(epochs[id]){"La tarea no pertenece a una sesión activa."})}
    suspend fun awaitReady():Boolean {
        ensureStarted("task")
        repeat(80) {
            if(g.paused.value)return false
            val phone=PhoneAccessibilityService.instance
            if(running.value && phone!=null && (!phone.screenAvailable() || phone.persistentShowing()))return true
            delay(25)
        }
        return false
    }
    fun dispatch(id:String) {
        if(!running.value || g.paused.value || tasks[id]?.isActive==true)return
        synchronized(tasks) {
            if(tasks[id]?.isActive==true)return
            val job=g.scope.launch(start=CoroutineStart.LAZY) {
                try{g.engine.runTask(id,foregroundManaged=true)}finally{tasks.remove(id)}
            }
            tasks[id]=job;job.start()
        }
    }
    fun wakeForTask():PowerManager.WakeLock?=runCatching{g.context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Campana:incoming").apply{acquire(120_000)}}.getOrNull()
    suspend fun tick() {
        if(g.paused.value){service?.stopSelf();return}
        withContext(Dispatchers.Main){PhoneAccessibilityService.instance?.syncPersistentControl()}
        val phone=PhoneAccessibilityService.instance
        phase.value=when {
            phone==null->"Esperando Accesibilidad"
            !connected()->"Esperando internet"
            !g.incomingWake.value->"Atención entrante desactivada"
            !SocialNotificationService.connected.value->"Reconectando notificaciones"
            g.visualWorking.value->"Operando una aplicación"
            g.busy.value->"Preparando respuesta de campaña"
            !phone.unlocked()->"Atento · pantalla bloqueada"
            else->if(g.messengerAccounts.verifiedNativeAccount.value)"Atento a WhatsApp y Messenger" else "WhatsApp atento · Messenger exige fanpage"
        }
        if(connected() && phone!=null)g.dao.dueTasks(System.currentTimeMillis()).sortedBy{if(it.kind=="REPLY")0 else 1}.forEach{dispatch(it.id)}
        if(connected() && phone!=null && !g.busy.value && !g.visualWorking.value)g.inbox.ensureObservation()
        if(!g.busy.value && !g.visualWorking.value && tasks.isEmpty()) {
            if(idleSince==0L)idleSince=SystemClock.uptimeMillis()
            if(SystemClock.uptimeMillis()-idleSince>=2000)withContext(Dispatchers.Main){phone?.returnToAgentWhenIdle()}
        } else idleSince=0L
        val now=System.currentTimeMillis()
        if(now-lastBeat>=30_000) {
            lastBeat=now
            val start=g.prefs.getLong("soak_started_at",0).takeIf{it>0 && g.prefs.getLong("soak_stopped_at",0)<it && g.prefs.getLong("soak_installed_at",0)==installedAt} ?: now.also{
                g.prefs.edit().putLong("soak_started_at",it).putLong("soak_stopped_at",0).putLong("soak_max_gap_ms",0).putLong("soak_installed_at",installedAt).apply()
            }
            val previous=if(start==now)now else g.prefs.getLong("runtime_heartbeat_at",now)
            val gap=(now-previous).coerceAtLeast(0)
            val maxGap=maxOf(g.prefs.getLong("soak_max_gap_ms",0),gap)
            val hours=(now-start)/3_600_000.0
            report.value="Sesión observada: %.2f h · Detectados: %d · Presentados: %d · Errores: %d · Mayor intervalo: %d s".format(hours,received,submitted,failed,maxGap/1000)
            g.prefs.edit().putLong("runtime_heartbeat_at",now).putLong("soak_max_gap_ms",maxGap).putString("runtime_report",report.value).apply()
            g.trace("runtime_heartbeat elapsed_s="+((now-start)/1000)+" network="+connected()+" notifications="+SocialNotificationService.connected.value+" accessibility="+(phone!=null))
            if(now-start>=24*60*60_000L && g.prefs.getLong("soak_reported_start",0)!=start) {
                g.prefs.edit().putLong("soak_reported_start",start).apply()
                g.dao.insert(org.campana.agent.data.ChatMessage(campaignId=g.activeCampaign.value,role="notice",text="Se completaron 24 horas de observación de esta sesión. "+report.value+". Este registro no confirma por sí solo la entrega de cada mensaje."))
            }
            if(!SocialNotificationService.connected.value)runCatching{android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(g.context,SocialNotificationService::class.java))}
            else SocialNotificationService.instance?.reconsiderIncoming()
            if(connected() && preparation?.isActive!=true)preparation=g.scope.launch {
                runCatching{g.postLinks.ensurePreparation()}.onFailure{if(it !is CancellationException)g.trace("preparation_retry_deferred type="+it.javaClass.simpleName)}
            }
        }
    }
    fun snapshot()="session_epoch=${gate.epoch} foreground=${running.value} phase=${phase.value}\n"+report.value
}
