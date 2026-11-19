package org.campana.agent.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Path
import android.os.SystemClock
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.campana.agent.CampaignApp
import org.campana.agent.data.Channel
import org.campana.agent.data.newId
import org.campana.agent.agent.ScreenRules
import org.campana.agent.agent.ActorEvidence
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Serializable data class ScreenNode(val id: Int, val text: String, val description: String,
    val resource: String, val editable: Boolean, val clickable: Boolean, val scrollable: Boolean,
    val left: Int, val top: Int, val right: Int, val bottom: Int, val hint:String="",val showingHint:Boolean=false,val enabled:Boolean=true,
    val selected:Boolean=false,val checked:Boolean=false,val uniqueId:String="",val nativeDescription:String="",val longClickable:Boolean=false)
@Serializable data class ScreenState(val id: String, val packageName: String, val windowId: Int,
    val capturedAt: Long, val nodes: List<ScreenNode>)

class PhoneAccessibilityService : AccessibilityService() {
    override fun dump(fd:java.io.FileDescriptor,writer:java.io.PrintWriter,args:Array<out String>) {
        if(org.campana.agent.BuildConfig.DEBUG && "--inbox-inspection" in args){writer.println(graph.inbox.diagnostic);return}
        if(org.campana.agent.BuildConfig.DEBUG && "--inbox-thread-inspection" in args){writer.println(graph.inbox.threadDiagnostic);return}
        if(org.campana.agent.BuildConfig.DEBUG && "--voice-inspection" in args){writer.println(graph.voiceExport.diagnostic);return}
        // Cached metadata only: never query Room, wait for a model, or expose tokens here.
        writer.println(graph.runtimeSnapshot())
    }
    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set
        val connected=kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    private var snapshot: ScreenState? = null
    private var facebookReview:ScreenRules.FacebookReview?=null
    private val nodes = mutableMapOf<Int,AccessibilityNodeInfo>()
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val screenshotMutex=kotlinx.coroutines.sync.Mutex()
    private var lastScreenshotRequestAt=0L
    @Volatile var screenshotProtected=false
        private set
    private var ownGestureUntil=0L
    private val main=Handler(Looper.getMainLooper())
    private val graph get()=(application as CampaignApp).graph
    private val controlOverlay by lazy { ControlOverlay(this,{recoverControl("recovery_button")},::outsideTouch) }
    @Volatile private var control:ControlSession?=null
    private val controllerInstance=newId()
    private var controlSerial=0L
    internal fun controlIdentity():Pair<String,Long>{requireControl();return controllerInstance to controlSerial}
    private var screenReceiverRegistered=false
    private val screenReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==Intent.ACTION_USER_PRESENT && !graph.paused.value)recoverControl("owner_unlocked")
            else {if(intent.action==Intent.ACTION_SCREEN_OFF){control?.job?.cancel();control?.let{finishControl(it)}};syncPersistentControl()}
        }
    }
    internal fun persistentShowing()=controlOverlay.showing
    internal fun returnToAgentWhenIdle() {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(control!=null || graph.busy.value || graph.visualWorking.value || graph.paused.value || !graph.prefs.getBoolean("ui_enabled",false) || !graph.runtime.running.value || !screenAvailable())return
        val pkg=runCatching{contentRoot().packageName?.toString()}.getOrNull()
        if(pkg in setOf(Channel.WHATSAPP.packageName,Channel.MESSENGER.packageName)) {
            startActivity(Intent(this,org.campana.agent.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            graph.trace("runtime_returned_to_agent_idle")
        }
    }
    /** Instrumented local-laboratory input. Never available to a release or a social window. */
    internal fun laboratoryTouch(rect:Rect,asAgent:Boolean,done:(Boolean)->Unit) {
        check(org.campana.agent.BuildConfig.DEBUG && control?.localOnly==true)
        val root=contentRoot()
        check(root.packageName?.toString()==packageName && root.findAccessibilityNodeInfosByViewId(packageName+":id/sandbox_recipient").isNotEmpty())
        requireControl()
        if(asAgent)ownGestureUntil=SystemClock.uptimeMillis()+1500 else ownGestureUntil=0
        val path=Path().apply{moveTo(rect.exactCenterX(),rect.exactCenterY())}
        val accepted=dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,70)).build(),object:GestureResultCallback(){
            override fun onCompleted(gestureDescription:GestureDescription?){done(true)}
            override fun onCancelled(gestureDescription:GestureDescription?){done(false)}
        },null)
        if(!accepted)done(false)
    }
    internal fun screenAvailable()=unlocked() && getSystemService(android.os.PowerManager::class.java).isInteractive
    internal fun syncPersistentControl() {
        check(Looper.myLooper()==Looper.getMainLooper())
        val show=graph.runtime.running.value && !graph.paused.value && graph.prefs.getBoolean("ui_enabled",false) && screenAvailable()
        if(show && !controlOverlay.showing)runCatching{controlOverlay.hide();controlOverlay.show()}.onFailure{graph.status.value="No se pudo mostrar Recuperar control."}
        if(!show && control==null)controlOverlay.hide()
    }
    private fun outsideTouch(eventTime:Long){if(eventTime>=graph.runtime.startedAt && eventTime>ownGestureUntil && (!graph.paused.value || control!=null))recoverControl("outside_touch age_ms="+(SystemClock.uptimeMillis()-eventTime))}
    internal inner class ControlSession internal constructor(internal val localOnly:Boolean,internal val job:Job?) : AutoCloseable {
        internal val startedAt=SystemClock.uptimeMillis()
        @Volatile internal var revoked=false
        override fun close(){onMain{finishControl(this)}}
    }
    private fun onMain(block:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())block() else main.post{block()}}
    /** Called only by the local executor or the explicit, app-owned laboratory test. */
    internal fun beginControl(localOnly:Boolean=false,job:Job?=null):ControlSession {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(instance===this && unlocked()) { "Accesibilidad no está disponible o el teléfono está bloqueado." }
        check(control==null) { "Ya hay una operación de pantalla en curso." }
        if(localOnly) {
            val root=contentRoot()
            check(root.packageName?.toString()==packageName && root.findAccessibilityNodeInfosByViewId(packageName+":id/sandbox_recipient").isNotEmpty())
        } else check(!graph.paused.value && graph.prefs.getBoolean("ui_enabled",false)) { "El propietario detuvo el control." }
        check(job?.isActive!=false)
        controlSerial++
        val session=ControlSession(localOnly,job)
        snapshot=null;nodes.clear();control=session
        try {if(!controlOverlay.showing){controlOverlay.hide();controlOverlay.show()};graph.visualWorking.value=true}
        catch(e:Exception){finishControl(session);throw IllegalStateException("No se pudo mostrar el control de recuperación. Revisa Accesibilidad.",e)}
        return session
    }
    internal suspend fun awaitControl(session:ControlSession) {
        repeat(60) {
            check(control===session && !session.revoked) { "El propietario recuperó el control." }
            if(controlOverlay.showing)return
            delay(16)
        }
        error("No se pudo mostrar el aviso de control.")
    }
    internal fun requireControl() {
        val session=control
        check(session!=null && !session.revoked && session.job?.isActive!=false && controlOverlay.showing) { "El propietario recuperó el control o el aviso no está disponible." }
        if(!session.localOnly)check(!graph.paused.value && graph.prefs.getBoolean("ui_enabled",false)) { "El propietario detuvo el control." }
    }
    internal fun controlToken():Any {requireControl();return checkNotNull(control)}
    internal fun navigateBack(pkg:String):Boolean {
        requireControl();check(contentRoot().packageName?.toString()==pkg)
        snapshot=null;nodes.clear();return performGlobalAction(GLOBAL_ACTION_BACK)
    }
    internal fun sameControl(token:Any):Boolean=control===token && control?.revoked==false && control?.job?.isActive!=false
    internal suspend fun uncoverTarget(rect:Rect) {
        requireControl()
        if(controlOverlay.avoid(rect))delay(100)
        requireControl()
    }
    internal suspend fun prepareFacebookShare(expected:String) {
        requireControl()
        facebookReview=null
        val session=control
        fun all(root:AccessibilityNodeInfo):List<AccessibilityNodeInfo> {
            val found=mutableListOf<AccessibilityNodeInfo>()
            fun walk(n:AccessibilityNodeInfo,depth:Int){if(depth>24 || found.size>=400)return;if(n.isVisibleToUser && !n.isPassword)found+=n;for(i in 0 until n.childCount)n.getChild(i)?.let{walk(it,depth+1)}}
            walk(root,0);return found
        }
        fun labels(n:AccessibilityNodeInfo)=listOf(n.text?.toString().orEmpty(),n.contentDescription?.toString().orEmpty()).map{org.campana.agent.data.normalized(it).trim('¿','?',' ')}
        fun dialog(root:AccessibilityNodeInfo):Boolean {
            if(root.packageName?.toString()!="com.facebook.katana")return false
            val list=all(root)
            return list.any{labels(it).any{s->s in setOf("quieres terminar la publicacion mas tarde","do you want to finish your post later")}} &&
                list.any{labels(it).any{s->s in setOf("descartar publicacion","discard post")}} &&
                list.any{labels(it).any{s->s in setOf("seguir editando","keep editing")}}
        }
        fun choose(root:AccessibilityNodeInfo,allowed:Set<String>) {
            check(control===session);requireControl()
            check(root.packageName?.toString()=="com.facebook.katana")
            val target=all(root).singleOrNull{it.isEnabled && canClick(it) && labels(it).any{label->label in allowed}}
                ?: error("El diálogo de Facebook cambió; no se descartó ningún contenido.")
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"Facebook no confirmó la acción del diálogo."}
            snapshot=null;nodes.clear()
        }
        var root=contentRoot()
        // Keeping editing is non-destructive. It restores a prior modal before checking the draft.
        if(dialog(root)) {choose(root,setOf("seguir editando","keep editing"));delay(450);requireControl()}
        val state=observe()
        if(state.packageName!="com.facebook.katana")error("Facebook no está en primer plano.")
        val actors=state.nodes.map{ActorEvidence(it.text,it.description,it.editable,it.left,it.top,it.right,it.bottom,it.enabled,it.showingHint)}
        val composer=ScreenRules.destinationMatches(state.nodes.map{it.resource to it.text},state.packageName,expected,actors)
        if(!composer) {
            check(state.nodes.none{listOf(it.text,it.description).any{s->org.campana.agent.data.normalized(s) in setOf("configuracion de la publicacion","post settings")}}) { "Hay una publicación en revisión. Vuelve al compositor y comprueba su autor y adjunto; no sustituyas el borrador." }
            return
        }
        check(ScreenRules.emptyFacebookComposer(state.packageName,"POST",expected,actors)){"Hay contenido en el compositor de Facebook. Revísalo antes de sustituirlo."}
        val close=state.nodes.singleOrNull{it.clickable && listOf(it.text,it.description).any{s->org.campana.agent.data.normalized(s) in setOf("cerrar","close")}}
            ?: error("No se encontró el cierre del compositor vacío.")
        uncoverTarget(Rect(close.left,close.top,close.right,close.bottom))
        check(act(state.id,close.id,"click","",expected)){"Facebook no confirmó el cierre del compositor vacío."}
        delay(450);check(control===session);requireControl()
        root=contentRoot()
        if(dialog(root)) {
            // This token exists only after the fresh, verified empty composer was closed above.
            // Never expose a generic discard/delete tool to the language model.
            choose(root,setOf("descartar publicacion","discard post"));delay(450);requireControl()
        }
        check(control===session)
    }
    private fun finishControl(session:ControlSession) {
        if(control!==session)return
        graph.postLinks.clear()
        facebookReview=null
        session.revoked=true;control=null;snapshot=null;nodes.clear()
        graph.visualWorking.value=false
        if(graph.runtime.running.value && !graph.paused.value && unlocked())syncPersistentControl() else controlOverlay.hide()
    }
    internal fun stopControl() {
        val session=control ?: run{onMain{syncPersistentControl()};return}
        session.revoked=true;session.job?.cancel()
        onMain{finishControl(session)}
    }
    private fun recoverControl(reason:String="recovery") { graph.trace("owner_takeover source="+reason);graph.pause(true);graph.status.value="Control recuperado. El agente está pausado." }
    private fun contentRoot():AccessibilityNodeInfo {
        val root=rootInActiveWindow ?: error("No hay una pantalla accesible.")
        // Recovery windows must never become a tool target or replace the underlying app snapshot.
        if(windows.any{it.id==root.windowId && it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}) {
            return windows.filter{it.type==AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)}
                .maxByOrNull{it.layer}?.root ?: error("No hay una aplicación accesible bajo el aviso.")
        }
        return root
    }
    private fun canClick(node:AccessibilityNodeInfo)=node.isClickable || node.isCheckable ||
        node.actionList.any { it.id==AccessibilityNodeInfo.ACTION_CLICK }
    private fun semanticText(node:AccessibilityNodeInfo):String {
        if(node.viewIdResourceName!="com.instagram.android:id/recipients_container")return node.text?.toString().orEmpty()
        val labels=mutableListOf<String>()
        fun collect(n:AccessibilityNodeInfo,depth:Int) {
            if(depth>4 || n.isPassword || n.isEditable || !n.isVisibleToUser)return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels+=it.take(200) }
            for(i in 0 until n.childCount)n.getChild(i)?.let { collect(it,depth+1) }
        }
        collect(node,0)
        return labels.distinct().joinToString(" | ").take(600)
    }
    private fun description(node:AccessibilityNodeInfo):String {
        val direct=node.contentDescription?.toString().orEmpty()
        if(!canClick(node))return direct.take(300)
        val labels=mutableListOf<String>()
        if(direct.isNotBlank() && !node.isCheckable)labels+=direct.take(150)
        fun childText(n:AccessibilityNodeInfo,depth:Int) {
            if(depth>3 || labels.size>=3 || n.isPassword || !n.isVisibleToUser)return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels+=it.take(100) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels+=it.take(100) }
            for(i in 0 until n.childCount)n.getChild(i)?.let { childText(it,depth+1) }
        }
        for(i in 0 until node.childCount)node.getChild(i)?.let { childText(it,1) }
        return labels.distinct().joinToString(" · ").take(300)
    }
    override fun onServiceConnected() {
        instance=this;connected.value=true
        if(!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this,screenReceiver,IntentFilter().apply{addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)},ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered=true
        }
        org.campana.agent.jobs.TaskWorker.kick(this)
        graph.runtime.ensureStarted("accessibility_connected")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(event?.eventType==AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            val graph=(application as CampaignApp).graph
            val session=control
            if((!graph.paused.value || session?.localOnly==true) && event.eventTime>=graph.runtime.startedAt && event.eventTime>ownGestureUntil)recoverControl("touch_interaction age_ms="+(SystemClock.uptimeMillis()-event.eventTime))
        }
    }
    override fun onInterrupt() { control?.job?.cancel();control?.let{finishControl(it)};snapshot=null;nodes.clear() }
    override fun onUnbind(intent:Intent?):Boolean {
        control?.job?.cancel();control?.let{finishControl(it)};controlOverlay.hide()
        if(instance===this){instance=null;connected.value=false}
        return super.onUnbind(intent)
    }
    override fun onConfigurationChanged(newConfig:Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching{controlOverlay.hide();if(control!=null || (graph.runtime.running.value && !graph.paused.value))controlOverlay.show()}.onFailure{recoverControl()}
    }
    override fun onDestroy() {
        control?.job?.cancel();control?.let{finishControl(it)};controlOverlay.hide()
        if(screenReceiverRegistered)unregisterReceiver(screenReceiver)
        screenReceiverRegistered=false;instance=null;connected.value=false;screenshotExecutor.shutdown();super.onDestroy()
    }
    fun unlocked(): Boolean = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun observe(): ScreenState=readScreen(true)
    internal fun peek():ScreenState=readScreen(false)
    private fun readScreen(rememberTargets:Boolean): ScreenState {
        check(screenAvailable()) { "La pantalla está apagada o bloqueada." }
        val root = contentRoot()
        val pkg = root.packageName?.toString() ?: ""
        check(Channel.forPackage(pkg) != null || graph.postLinks.extraSurface(pkg) || graph.voiceExport.extraSurface(pkg) || (pkg == packageName && root.findAccessibilityNodeInfosByViewId(packageName + ":id/sandbox_recipient").isNotEmpty())) {
            "La pantalla actual no pertenece a una red autorizada ni al laboratorio."
        }
        if(rememberTargets)nodes.clear()
        val result = mutableListOf<ScreenNode>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || result.size >= 250) return
            if (node.isVisibleToUser && !node.isPassword) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                val id = result.size + 1
                result += ScreenNode(id, semanticText(node).take(if(node.isEditable)6000 else 8000), description(node),
                    node.viewIdResourceName ?: "", node.isEditable, canClick(node), node.isScrollable,
                    bounds.left,bounds.top,bounds.right,bounds.bottom,node.hintText?.toString().orEmpty(),node.isShowingHintText,node.isEnabled,node.isSelected,node.isChecked,
                    if(android.os.Build.VERSION.SDK_INT>=33)node.uniqueId.orEmpty() else "",node.contentDescription?.toString().orEmpty().take(8000),node.isLongClickable)
                if(rememberTargets)nodes[id] = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it,depth+1) }
        }
        visit(root,0)
        return ScreenState(newId(),pkg,root.windowId,System.currentTimeMillis(),result).also { if(rememberTargets)snapshot=it }
    }
    fun remembered(id:String,maxAgeMillis:Long=15_000):ScreenState {
        val value=checkNotNull(snapshot) { "Observa primero la pantalla." }
        check(value.id==id && System.currentTimeMillis()-value.capturedAt<maxAgeMillis) { "La observación venció." }
        return value
    }
    internal fun verifiedFacebookReview(state:ScreenState,expected:String):Boolean {
        requireControl()
        return ScreenRules.facebookReviewMatches(facebookReview,state.packageName,expected,state.windowId,SystemClock.uptimeMillis(),
            state.nodes.map{ActorEvidence(it.text,it.description,it.editable,it.left,it.top,it.right,it.bottom,it.enabled,it.showingHint)})
    }
    internal fun resetFacebookReview(){facebookReview=null}
    internal suspend fun awaitFacebookReview() {
        val pending=facebookReview ?: return
        val session=control
        // Facebook opens its review as a new native window. Bind that window locally
        // immediately after the verified Next, never from model-supplied evidence.
        repeat(15) {
            requireControl();check(control===session)
            if(SystemClock.uptimeMillis()-pending.advancedAt !in 0L..5000L){facebookReview=null;return}
            val state=observe()
            val bound=pending.copy(windowId=state.windowId)
            val actors=state.nodes.map{ActorEvidence(it.text,it.description,it.editable,it.left,it.top,it.right,it.bottom,it.enabled,it.showingHint)}
            if(ScreenRules.facebookReviewMatches(bound,state.packageName,pending.account,state.windowId,SystemClock.uptimeMillis(),actors)) {
                facebookReview=bound;return
            }
            delay(150)
        }
        facebookReview=null
    }
    fun act(snapshotId: String, nodeId: Int, action: String, text: String, destination:String="",expectedDraft:String=""): Boolean {
        check(unlocked()) { "Desbloquea el teléfono." }
        requireControl()
        val state = snapshot ?: error("Observa de nuevo la pantalla.")
        if(control?.localOnly==true)check(state.packageName==packageName) { "La prueba local no puede operar otras aplicaciones." }
        if(state.packageName!=packageName) {
            val graph=(application as CampaignApp).graph
            check(!graph.paused.value && graph.prefs.getBoolean("ui_enabled",false)) { "El propietario detuvo el control." }
        }
        check(state.id == snapshotId && System.currentTimeMillis()-state.capturedAt < 15_000) { "La observación venció; observa de nuevo." }
        val root = contentRoot()
        check(root.packageName?.toString() == state.packageName && root.windowId == state.windowId) { "La pantalla cambió." }
        val node = nodes[nodeId] ?: error("Elemento desconocido.")
        check(node.refresh() && node.isVisibleToUser && !node.isPassword) { "El elemento cambió." }
        val recorded = state.nodes.first { it.id == nodeId }
        check(semanticText(node).take(if(node.isEditable)6000 else 8000) == recorded.text &&
            description(node) == recorded.description && node.viewIdResourceName.orEmpty()==recorded.resource &&
            node.isEditable==recorded.editable && node.isEnabled==recorded.enabled) { "El elemento cambió de contenido." }
        var advanceFacebook=false
        if(destination.isNotBlank()) {
            val evidence=mutableListOf<Pair<String,String>>()
            val actors=mutableListOf<ActorEvidence>()
            fun inspect(n:AccessibilityNodeInfo,depth:Int) {
                if(depth>24)return
                val bounds=Rect();n.getBoundsInScreen(bounds)
                if(n.isVisibleToUser && !n.isPassword){
                    evidence+=(n.viewIdResourceName.orEmpty() to semanticText(n))
                    actors+=ActorEvidence(semanticText(n),description(n),n.isEditable,bounds.left,bounds.top,bounds.right,bounds.bottom,n.isEnabled,n.isShowingHintText)
                }
                for(i in 0 until n.childCount)n.getChild(i)?.let { inspect(it,depth+1) }
            }
            inspect(root,0)
            val direct=ScreenRules.destinationMatches(evidence,state.packageName,destination,actors)
            val submission=action=="click" && ScreenRules.isSubmission(recorded.text+" "+recorded.description,recorded.resource)
            if(expectedDraft.isNotBlank())check(submission && actors.singleOrNull{it.editable && it.enabled}?.let{!it.showingHint && it.text==expectedDraft}==true){"El borrador cambió antes del envío; se detuvo la acción."}
            check(direct || (submission && ScreenRules.facebookReviewMatches(facebookReview,state.packageName,destination,root.windowId,SystemClock.uptimeMillis(),actors))) { "La identidad del destino cambió. Se detuvo el envío." }
            advanceFacebook=action=="click" && ScreenRules.facebookAdvance(state.packageName,destination,actors,recorded.text+" "+recorded.description)
            if(action=="write" && ScreenRules.emptyWhatsAppHint(state.packageName,recorded.resource,recorded.text,state.nodes.map{it.resource to it.text}))
                check(ScreenRules.emptyWhatsAppHint(state.packageName,recorded.resource,semanticText(node),evidence)) { "El compositor cambió. Revisa el borrador antes de sustituirlo." }
            if(action=="click" && state.packageName=="com.facebook.katana" && listOf(recorded.text,recorded.description).any{it.trim().equals("Cerrar",true) || it.trim().equals("Close",true)})
                check(ScreenRules.emptyFacebookComposer(state.packageName,"POST",destination,actors)) { "Solo se puede cerrar automáticamente un compositor verificado y vacío." }
        }
        // Only the immediately preceding verified Next can authorize the final review.
        // Observations preserve it; every other action and control handoff consume it.
        facebookReview=null
        val performed = when (action) {
            "long_click" -> {
                var target=node;var hops=0
                while(!target.isLongClickable && hops++<6){target=target.parent ?: break}
                ownGestureUntil=SystemClock.uptimeMillis()+1500
                val performed=target.isLongClickable && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                if(performed)true
                else {val bounds=Rect();node.getBoundsInScreen(bounds);val path=android.graphics.Path().apply{moveTo(bounds.exactCenterX(),bounds.exactCenterY())};dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path,0,650)).build(),null,null)}
            }
            "write" -> {
                check(node.isEditable && text.length <= 6000)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
                })
            }
            "click" -> {
                var target=node
                var hops=0
                while(!canClick(target) && hops<3) {
                    val parent=target.parent ?: break
                    target=parent;hops++
                }
                check(canClick(target) && target.isEnabled && target.isVisibleToUser && !target.isPassword) { "No hay un contenedor pulsable para esta etiqueta." }
                    // Some native virtual controls synthesize input while handling ACTION_CLICK.
                    // Mark the operation before the Binder call, not only its gesture fallback.
                    ownGestureUntil=SystemClock.uptimeMillis()+500
                    val clicked=target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if(clicked || destination.isNotBlank()) clicked
                else {
                    // Navigation fallback uses the verified element bounds, never coordinates supplied by the model.
                    val rect=Rect();target.getBoundsInScreen(rect)
                    check(rect.width()>0 && rect.height()>0 && rect.centerX() in 0 until resources.displayMetrics.widthPixels &&
                        rect.centerY() in 0 until resources.displayMetrics.heightPixels) { "Elemento fuera de la pantalla." }
                    check(!controlOverlay.covers(rect)) { "El aviso cubre este control. Observa de nuevo antes de continuar." }
                    val path=Path().apply { moveTo(rect.exactCenterX(),rect.exactCenterY()) }
                    ownGestureUntil=SystemClock.uptimeMillis()+1200
                    val session=control
                    dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,70)).build(),
                        object:GestureResultCallback() {
                            override fun onCompleted(gestureDescription:GestureDescription?) { if(control===session)ownGestureUntil=SystemClock.uptimeMillis()+120 }
                            override fun onCancelled(gestureDescription:GestureDescription?) {
                                if(control===session){ownGestureUntil=0;if(graph.visualWorking.value)graph.pause(true)}
                            }
                        },null)
                }
            }
            "scroll" -> { check(node.isScrollable); node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            "scroll_back" -> { check(node.isScrollable); node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
            else -> error("Acción de pantalla no admitida.")
        }
        if(performed && advanceFacebook)facebookReview=ScreenRules.FacebookReview(destination,state.windowId,SystemClock.uptimeMillis())
        snapshot = null; nodes.clear()
        return performed
    }
    suspend fun screenshot(windowId:Int):ByteArray=screenshotMutex.withLock {
        delay(ScreenRules.captureWaitMillis(lastScreenshotRequestAt,SystemClock.uptimeMillis()))
        requireControl()
        lastScreenshotRequestAt=SystemClock.uptimeMillis()
        screenshotProtected=false
        captureWindow(windowId)
    }
    private suspend fun captureWindow(windowId:Int): ByteArray = suspendCoroutine { continuation ->
        requireControl()
        if(android.os.Build.VERSION.SDK_INT<34)error("La captura sin superposiciones requiere Android 14. Usa la lectura de elementos con observe_screen en este teléfono.")
        check(contentRoot().windowId==windowId) { "La ventana cambió. Observa de nuevo antes de capturar." }
        val session=control
        takeScreenshotOfWindow(windowId,screenshotExecutor,object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hardware = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(hardware,result.colorSpace)
                try {
                    check(control===session && session?.revoked==false)
                    checkNotNull(bitmap)
                    val width = minOf(720,bitmap.width)
                    val scaled = Bitmap.createScaledBitmap(bitmap,width,bitmap.height*width/bitmap.width,true)
                    val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG,65,out)
                    if (scaled !== bitmap) scaled.recycle()
                    continuation.resume(out.toByteArray())
                } catch (_: Exception) { continuation.resume(byteArrayOf()) }
                finally { bitmap?.recycle(); hardware.close() }
            }
            override fun onFailure(errorCode: Int) { screenshotProtected=errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW;graph.trace("screen_capture_failed code="+errorCode+" window="+windowId);continuation.resume(byteArrayOf()) }
        })
    }
}
