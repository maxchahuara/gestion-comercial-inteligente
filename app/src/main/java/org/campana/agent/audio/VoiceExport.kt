package org.campana.agent.audio

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.*
import org.campana.agent.AppGraph
import org.campana.agent.R
import org.campana.agent.agent.*
import org.campana.agent.data.*
import org.campana.agent.phone.*
import java.io.File

/** Receives one selected native voice attachment; cannot forward to another contact or app. */
class VoiceExport(private val g:AppGraph) {
    @Volatile var diagnostic="{}";private set
    fun remember(state:ScreenState){if(org.campana.agent.BuildConfig.DEBUG)diagnostic=org.campana.agent.net.json.encodeToString(ScreenState.serializer(),state)}
    private data class Pending(val task:AgentTask,val control:Any,val until:Long,@Volatile var chosen:Boolean=false,@Volatile var armed:Boolean=false,@Volatile var selected:Boolean=false,@Volatile var file:File?=null)
    @Volatile private var pending:Pending?=null
    private val receiver=ComponentName(g.context,VoiceReceiverActivity::class.java)
    init { enabled(false) }
    fun active(task:AgentTask)=pending?.let{it.task.id==task.id && valid(it)}==true
    private fun valid(p:Pending)=!g.paused.value && g.incomingWake.value && SystemClock.uptimeMillis()<p.until && PhoneAccessibilityService.instance?.sameControl(p.control)==true
    fun ready(id:String)=pending?.let{it.task.id==id && valid(it) && it.file?.isFile==true}==true
    fun file(task:AgentTask)=pending?.takeIf{it.task.id==task.id && valid(it)}?.file
    fun extraSurface(pkg:String)=pending?.let{valid(it) && it.armed && (PostLinkRules.chooserPackage(pkg) || pkg==g.context.packageName)}==true
    private fun enabled(value:Boolean){g.context.packageManager.setComponentEnabledSetting(receiver,if(value)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)}
    suspend fun begin(task:AgentTask) {
        val source=checkNotNull(g.incomingIdentities.uiReplySource(task));check(source.voiceNote && source.audioUri.isBlank())
        withContext(Dispatchers.Main){clear();val service=checkNotNull(PhoneAccessibilityService.instance);service.requireControl();pending=Pending(task,service.controlToken(),SystemClock.uptimeMillis()+240_000)}
    }
    fun clear(id:String?=null){val p=pending;if(id!=null && p?.task?.id!=id)return;pending=null;p?.file?.delete();enabled(false)}
    suspend fun act(task:AgentTask,service:PhoneAccessibilityService,state:ScreenState,node:ScreenNode,action:String):Boolean {
        val p=checkNotNull(pending);check(active(task));g.engine.authorized(task)
        return withContext(Dispatchers.Main) {
            val beforeChosen=p.chosen;val beforeArmed=p.armed;val beforeSelected=p.selected;var performed=false
            try {
            val fresh=service.observe();check(fresh.packageName==state.packageName && fresh.windowId==state.windowId)
            val n=PostLinkRules.liveNode(fresh.nodes,node) ?: error("La pantalla cambió.")
            val label=normalized(n.text+" "+n.nativeDescription.ifBlank{n.description}).trim()
            if(PostLinkRules.chooserPackage(fresh.packageName)) {
                check(p.armed && action=="click")
                val appLabel = g.context.getString(R.string.app_name)
                val targetLabel = g.context.getString(R.string.voice_share_label)
                val own = PostLinkRules.ownShareTarget(label, targetLabel, appLabel)
                check(own || label in setOf("mas","mas aplicaciones","mas opciones","more","more apps")){"Solo se puede compartir la voz hacia $appLabel."}
                if (own) {
                    val packageManager = g.context.packageManager
                    val matches = packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("audio/*"), PackageManager.MATCH_DEFAULT_ONLY)
                        .filter { normalized(it.loadLabel(packageManager).toString()) == normalized(targetLabel) }
                    val target = matches.singleOrNull()?.activityInfo
                    check(target?.packageName == g.context.packageName && target.name == receiver.className)
                    p.selected = true
                }
            } else {
                check(fresh.packageName==Channel.valueOf(task.channel).packageName)
                if(action=="long_click") {
                    check(!p.armed && !p.selected){"Compartir ya está iniciado; continúa en el selector del archivo."}
                    val actors=fresh.nodes.map{ActorEvidence(it.text,it.description,it.editable,it.left,it.top,it.right,it.bottom,it.enabled,it.showingHint)}
                    check(ScreenRules.destinationMatches(fresh.nodes.map{it.resource to it.text},fresh.packageName,task.recipient,actors)){"No se verificó la conversación del audio."}
                    val composer=fresh.nodes.filter{it.editable}.minOfOrNull{it.top} ?: error("No hay un compositor de conversación.")
                    val header=fresh.nodes.filter{!it.editable && ((it.resource.substringAfterLast('/') in setOf("conversation_contact_name","thread_name","thread_title","chat_title","recipient_name") && sameContact(it.text,task.recipient)) || normalized(it.nativeDescription).startsWith(normalized(task.recipient)+", detalles de la conversacion"))}.maxOfOrNull{it.bottom} ?: error("No hay una cabecera nativa verificable.")
                    val candidates=fresh.nodes.filter{!it.editable && it.top>=header && it.bottom<=composer && Regex("audio|voz|voice").containsMatchIn(normalized(it.text+" "+it.nativeDescription+" "+it.resource)) && !Regex("voice_note_btn|record").containsMatchIn(it.resource)}
                    val last=candidates.maxOfOrNull{it.bottom} ?: error("No hay una nota de voz visible.")
                    check(candidates.any{it.id==n.id} && n.bottom==last){"Solo puede seleccionarse la última nota de voz visible del mensaje entrante."}
                    p.chosen=true
                } else if(action=="click") {
                    check(p.chosen && label in setOf("mas","more","mas opciones","more options","compartir","share")){"Solo se admite el menú de compartir del audio seleccionado."}
                    if(label in setOf("compartir","share")){p.armed=true;enabled(true)}
                } else check(action=="scroll" && n.scrollable && !p.chosen)
            }
            service.uncoverTarget(Rect(n.left,n.top,n.right,n.bottom));service.act(fresh.id,n.id,action,"").also{performed=it}
            }finally{if(!performed){p.chosen=beforeChosen;p.selected=beforeSelected;if(p.armed!=beforeArmed){p.armed=beforeArmed;enabled(beforeArmed)}}}
        }
    }
    suspend fun back(task:AgentTask):Boolean=withContext(Dispatchers.Main){check(active(task));val service=checkNotNull(PhoneAccessibilityService.instance);val state=service.peek();check(state.packageName==Channel.valueOf(task.channel).packageName || PostLinkRules.chooserPackage(state.packageName));service.navigateBack(state.packageName)}
    suspend fun restore(task:AgentTask) {
        delay(500)
        withContext(Dispatchers.Main){check(active(task));val service=checkNotNull(PhoneAccessibilityService.instance);var state=service.peek()
            check(state.packageName==Channel.valueOf(task.channel).packageName)
            fun destination(s:ScreenState)=ScreenRules.destinationMatches(s.nodes.map{it.resource to it.text},s.packageName,task.recipient,s.nodes.map{ActorEvidence(it.text,it.description,it.editable,it.left,it.top,it.right,it.bottom,it.enabled,it.showingHint)})
            if(!destination(state)){service.navigateBack(state.packageName);delay(400);state=service.peek()}
            check(destination(state)){"No se pudo restaurar la conversación del audio."}
        }
    }
    suspend fun receive(intent:Intent):Boolean {
        val p=withContext(Dispatchers.Main){pending?.takeIf{valid(it) && it.armed && it.selected}} ?: return false
        check(intent.action==Intent.ACTION_SEND && !intent.hasExtra(Intent.EXTRA_INTENT) && (intent.type?.startsWith("audio/")==true || intent.type=="application/ogg"))
        val uri=androidx.core.content.IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,Uri::class.java) ?: return false
        check(uri.scheme=="content" && g.context.packageManager.resolveContentProvider(uri.authority.orEmpty(),0)?.packageName==Channel.valueOf(p.task.channel).packageName)
        if(intent.clipData!=null)check(intent.clipData!!.itemCount==1 && intent.clipData!!.getItemAt(0).uri==uri)
        val dir=File(g.context.cacheDir,"voice").apply{mkdirs()};val file=File(dir,newId()+".audio")
        try {
            withContext(Dispatchers.IO){g.context.contentResolver.openInputStream(uri)!!.use{input->file.outputStream().use{output->val b=ByteArray(16384);var bytes=0L;while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;bytes+=n;check(bytes<=25*1024*1024);output.write(b,0,n)}}}}
            withContext(Dispatchers.Main){check(pending===p && valid(p));p.file=file;enabled(false)}
            g.trace("voice_file_received channel="+p.task.channel);return true
        }catch(e:Exception){file.delete();throw e}
    }
}
