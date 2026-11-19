package org.campana.agent

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.campana.agent.phone.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ControlOverlayTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var g:AppGraph
    private lateinit var automation:UiAutomation
    private lateinit var scenario:ActivityScenario<SandboxActivity>
    private var previousPause=false
    private var previousRotation=0
    private var previousAutoRotation=1
    private val service get()=checkNotNull(PhoneAccessibilityService.instance)
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun shell(command:String){automation.executeShellCommand(command).use{p->java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}}}
    private fun waitFor(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+6000
        while(SystemClock.elapsedRealtime()<deadline){if(condition())return;SystemClock.sleep(50)}
        assertTrue(message,condition())
    }
    @Before fun prepareOnlyTheLocalLab() {
        g=(context.applicationContext as CampaignApp).graph;runBlocking{g.ready.await()}
        previousPause=g.paused.value;g.pause(true)
        assertFalse("Desbloquea el teléfono de pruebas antes de iniciar.",context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        previousRotation=Settings.System.getInt(context.contentResolver,Settings.System.USER_ROTATION,0)
        previousAutoRotation=Settings.System.getInt(context.contentResolver,Settings.System.ACCELEROMETER_ROTATION,1)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
        val component="org.campana.agent/org.campana.agent.phone.PhoneAccessibilityService"
        val before=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertTrue(component in before.split(":"));require(before.matches(Regex("[A-Za-z0-9_.$:/]*")))
        if(PhoneAccessibilityService.instance==null) {
            val rest=before.split(":").filter{it!=component}.joinToString(":")
            shell(if(rest.isEmpty())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services "+rest)
            SystemClock.sleep(1200);shell("settings put secure enabled_accessibility_services "+before);shell("settings put secure accessibility_enabled 1")
        }
        waitFor("Servicio previamente autorizado conectado"){PhoneAccessibilityService.instance!=null}
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        scenario=ActivityScenario.launch(Intent(context,SandboxActivity::class.java))
        waitFor("Laboratorio propio disponible"){automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("org.campana.agent:id/sandbox_input")?.isNotEmpty()==true}
    }
    @After fun returnThePhoneAndKeepOwnerState() {
        if(::g.isInitialized){g.pause(true);main{PhoneAccessibilityService.instance?.stopControl()}}
        if(::scenario.isInitialized)scenario.close()
        if(::automation.isInitialized){automation.setRotation(previousRotation);if(previousAutoRotation==1)automation.setRotation(UiAutomation.ROTATION_UNFREEZE)}
        if(::g.isInitialized)g.pause(previousPause)
    }
    private fun recoverButton():AccessibilityNodeInfo?=automation.windows
        .filter{it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY}
        .firstNotNullOfOrNull{it.root?.findAccessibilityNodeInfosByViewId("org.campana.agent:id/control_recover")?.firstOrNull()}
    private fun textNode(label:String):AccessibilityNodeInfo? {
        fun find(node:AccessibilityNodeInfo?,depth:Int):AccessibilityNodeInfo? {
            if(node==null || depth>30)return null
            if(node.text?.toString()?.contains(label)==true)return node
            for(index in 0 until node.childCount)find(node.getChild(index),depth+1)?.let{return it}
            return null
        }
        return find(automation.rootInActiveWindow,0)
    }
    private fun begin():PhoneAccessibilityService.ControlSession {
        var session:PhoneAccessibilityService.ControlSession?=null
        main{session=service.beginControl(localOnly=true)}
        waitFor("Aviso de recuperación visible"){recoverButton()!=null}
        return session!!
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("haloCaptures")!="true")return
        waitFor("Laboratorio propio en primer plano"){
            var own=false
            scenario.onActivity{own=it.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && it.window.decorView.hasWindowFocus()}
            own
        }
        val image=checkNotNull(automation.takeScreenshot())
        val folder=File(context.getExternalFilesDir(null),"halo-qa").apply{mkdirs()}
        File(folder,name+".png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun overlayPreservesRealLocalReadingWritingAndClicking() {
        assertNull(recoverButton());assertFalse(g.visualWorking.value)
        val session=begin()
        try {
            var observed:ScreenState?=null
            main{observed=service.observe()}
            assertTrue(observed!!.nodes.any{it.resource.endsWith("/sandbox_input")})
            assertTrue(observed!!.nodes.none{it.resource.endsWith("/control_recover") || it.resource.endsWith("/control_status")})
            main{val input=observed!!.nodes.first{it.resource.endsWith("/sandbox_input")};assertTrue(service.act(observed!!.id,input.id,"write","Prueba con halo azul"))}
            main{observed=service.observe();val button=observed!!.nodes.first{it.resource.endsWith("/sandbox_send")};assertTrue(service.act(observed!!.id,button.id,"click",""))}
            waitFor("Resultado local sin alterar la app"){
                var found=false;main{found=service.observe().nodes.any{it.text.contains("Recibido localmente: Prueba con halo azul")}};found
            }
            capture("01-halo-control-local")
        } finally {main{session.close()}}
        waitFor("Halo y botón retirados al terminar"){recoverButton()==null && !g.visualWorking.value}
        capture("02-fin-sin-halo")
    }
    @Test fun laboratoryProbeVerifiesFreshResultAndReleasesControl() {
        waitFor("Botón del laboratorio publicado"){textNode("Probar Accesibilidad")!=null}
        val target=checkNotNull(textNode("Probar Accesibilidad"))
        var button=target
        while(!button.isClickable)button=button.parent ?: break
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("La prueba completa confirma el efecto observado"){
            textNode("Lectura, escritura y pulsación comprobadas en esta pantalla.")!=null
        }
        assertFalse(g.visualWorking.value);assertNull(recoverButton())
    }
    @Test fun physicalRecoveryTapCancelsPendingActionsAndKeepsThePhonePaused()=runBlocking {
        val ready=CompletableDeferred<ScreenState>();var continued=false
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.Main).launch {
            val session=service.beginControl(localOnly=true,job=currentCoroutineContext()[Job])
            try {
                service.awaitControl(session);ready.complete(service.observe())
                delay(10000)
                val state=service.observe();val input=state.nodes.first{it.resource.endsWith("/sandbox_input")}
                service.act(state.id,input.id,"write","Esta acción debe cancelarse");continued=true
            } finally {withContext(NonCancellable+Dispatchers.Main){session.close()}}
        }
        try {
            val old=withTimeout(6000){ready.await()}
            waitFor("Botón real accesible"){recoverButton()!=null}
            val bounds=Rect();recoverButton()!!.getBoundsInScreen(bounds)
            capture("03-antes-de-recuperar")
            val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try{assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()}
            }
            withTimeout(5000){worker.join()}
            assertTrue(worker.isCancelled);assertFalse(continued);assertTrue(g.paused.value)
            waitFor("Control devuelto sin superposición"){recoverButton()==null && !g.visualWorking.value}
            main{assertThrows(IllegalStateException::class.java){service.act(old.id,old.nodes.first{it.resource.endsWith("/sandbox_input")}.id,"write","No debe ejecutarse")}}
            val newer=begin()
            try {main{assertThrows(IllegalStateException::class.java){service.act(old.id,old.nodes.first{it.resource.endsWith("/sandbox_input")}.id,"write","Observación antigua")}}}
            finally{main{newer.close()}}
            capture("04-control-recuperado")
        } finally {worker.cancelAndJoin()}
    }
    @Test fun pauseInterruptAndOldCleanupCannotLeaveAnOrphanOverlay() {
        main{assertThrows(IllegalStateException::class.java){service.beginControl()}}
        val old=begin()
        val oldTouch=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_INTERACTION_START).apply{eventTime=0}
        try {main{service.onAccessibilityEvent(oldTouch)};assertTrue("Un toque anterior no cancela la nueva sesión",g.visualWorking.value)}finally{oldTouch.recycle()}
        main{g.pause(true)}
        waitFor("Pausa retira el aviso"){recoverButton()==null}
        val newer=begin();main{old.close()}
        assertNotNull(recoverButton());assertTrue(g.visualWorking.value)
        main{service.onInterrupt()}
        waitFor("Interrupción retira el aviso"){recoverButton()==null && !g.visualWorking.value}
        assertTrue(g.paused.value);main{newer.close()}
    }
    @Test fun recoveryDoesNotWaitForAnUncancellableResponseOrResumeItsOldLease()=runBlocking {
        val ready=CompletableDeferred<Unit>();val response=CompletableDeferred<Unit>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val worker=scope.launch {
            val session=service.beginControl(localOnly=true,job=currentCoroutineContext()[Job])
            try {service.awaitControl(session);ready.complete(Unit);withContext(NonCancellable){response.await()}}
            finally {withContext(NonCancellable+Dispatchers.Main){session.close()}}
        }
        try {
            withTimeout(6000){ready.await()}
            waitFor("Botón publicado en Accesibilidad"){recoverButton()!=null}
            assertTrue(recoverButton()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Recuperación sin esperar la respuesta"){recoverButton()==null && !g.visualWorking.value}
            assertFalse(worker.isCompleted);assertTrue(g.paused.value)
            val newer=begin()
            try {response.complete(Unit);withTimeout(5000){worker.join()};assertNotNull(recoverButton());assertTrue(g.visualWorking.value)}
            finally {main{newer.close()}}
        } finally {response.complete(Unit);worker.cancelAndJoin();scope.cancel()}
    }
    @Test fun cardMovesAwayFromTheNextTargetWithoutLosingRecovery()=runBlocking {
        val session=begin()
        try {
            val before=Rect();recoverButton()!!.getBoundsInScreen(before)
            withContext(Dispatchers.Main){service.uncoverTarget(before)}
            waitFor("El aviso deja libre el objetivo"){
                val after=Rect();recoverButton()?.getBoundsInScreen(after)
                !after.isEmpty && !after.contains(before.centerX(),before.centerY())
            }
            assertTrue(g.visualWorking.value);capture("07-aviso-reubicado")
        } finally {main{session.close()}}
    }
    @Test fun agentWindowCaptureExcludesTheHaloAndRecoveryButton()=runBlocking {
        assumeTrue(android.os.Build.VERSION.SDK_INT>=34)
        val session=begin()
        try {
            val bytes=withContext(Dispatchers.Main){val state=service.observe();service.screenshot(state.windowId)}
            assertTrue("Captura de ventana disponible",bytes.isNotEmpty())
            val agent=checkNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            val humanRaw=checkNotNull(automation.takeScreenshot())
            val human=checkNotNull(humanRaw.copy(android.graphics.Bitmap.Config.ARGB_8888,false))
            fun bluePixels(bitmap:android.graphics.Bitmap):Int {
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                return pixels.count{val r=(it ushr 16) and 255;val g=(it ushr 8) and 255;val b=it and 255;b>150 && b>r+45 && b>g+30}
            }
            try {
                assertTrue("El humano ve el halo azul",bluePixels(human)>1000)
                assertEquals("El agente recibe el laboratorio sin las capas azules",0,bluePixels(agent))
                if(InstrumentationRegistry.getArguments().getString("haloCaptures")=="true") {
                    val folder=File(context.getExternalFilesDir(null),"halo-qa").apply{mkdirs()}
                    File(folder,"08-imagen-limpia-del-agente.jpg").writeBytes(bytes)
                    File(folder,"09-pantalla-visible-al-humano.png").outputStream().use{human.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
            } finally {agent.recycle();human.recycle();humanRaw.recycle()}
        } finally {main{session.close()}}
    }
    @Test fun systemServiceDisconnectionRemovesTheOverlayAndRestoresExistingGrant() {
        val before=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        require(before.matches(Regex("[A-Za-z0-9_.$:/]*")))
        val component="org.campana.agent/org.campana.agent.phone.PhoneAccessibilityService"
        val rest=before.split(":").filter{it!=component}.joinToString(":")
        val session=begin()
        try {
            shell(if(rest.isEmpty())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services "+rest)
            waitFor("Desconectar el servicio devuelve el control"){recoverButton()==null && !g.visualWorking.value && PhoneAccessibilityService.instance==null}
            assertTrue(g.paused.value)
        } finally {
            main{session.close()}
            shell("settings put secure enabled_accessibility_services "+before);shell("settings put secure accessibility_enabled 1")
            waitFor("Concesión y servicio restaurados"){PhoneAccessibilityService.instance!=null}
        }
        assertEquals(before,Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
    @Test fun keyboardAndRotationKeepTheRecoveryButtonWithinTheScreen() {
        val session=begin()
        try {
            scenario.onActivity { activity->val input=activity.findViewById<EditText>(R.id.sandbox_input);input.requestFocus();activity.getSystemService(InputMethodManager::class.java).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT)}
            waitFor("Teclado del teléfono visible"){automation.windows.any{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
            fun assertButtonFits() {
                val rect=Rect();checkNotNull(recoverButton()).getBoundsInScreen(rect)
                var screen:Rect?=null;main{screen=service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds}
                assertTrue("Botón "+rect+" dentro del área física "+screen,screen!!.contains(rect));assertTrue(rect.height()>=48*context.resources.displayMetrics.density)
            }
            assertButtonFits();capture("05-halo-con-teclado")
            automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            waitFor("Ventana adaptada a horizontal"){
                var horizontal=false;main{horizontal=service.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
                horizontal && recoverButton()!=null
            }
            var lastBounds=Rect();var stableSince=SystemClock.elapsedRealtime()
            waitFor("La animación de rotación terminó y el aviso quedó estable"){
                val bounds=Rect();recoverButton()?.getBoundsInScreen(bounds)
                var screen:Rect?=null;main{screen=service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds}
                val now=SystemClock.elapsedRealtime()
                if(bounds!=lastBounds || bounds.isEmpty || !screen!!.contains(bounds)){lastBounds=Rect(bounds);stableSince=now;false}
                else now-stableSince>=650
            }
            capture("06-halo-horizontal");assertButtonFits()
        } finally {main{session.close()}}
    }
}
