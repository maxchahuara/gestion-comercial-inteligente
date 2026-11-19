package org.campana.agent.phone

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import org.campana.agent.*

/** Debug-only test running inside the real phone process and its own local view.
 * No instrumentation restart, external UI automation, social messages or fake notifications. */
class NativeSessionQaActivity:ComponentActivity() {
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var result:TextView
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(50,160,50,50)}
        layout.addView(TextView(this).apply{text="Prueba nativa de sesión continua";textSize=22f})
        layout.addView(TextView(this).apply{id=R.id.sandbox_recipient;text="Contacto de prueba local";textSize=18f})
        val input=EditText(this).apply{id=R.id.sandbox_input;hint="Texto local";minHeight=150}
        layout.addView(input)
        result=TextView(this).apply{id=R.id.sandbox_status;textSize=18f}
        layout.addView(Button(this).apply{id=R.id.sandbox_send;text="Enviar prueba local";setOnClickListener{result.text="Recibido localmente: "+input.text}})
        layout.addView(result);setContentView(layout)
        work.launch {runCatching{verify()}.onSuccess{persistent->result.text="PASS: halo, lectura, toque y recuperación. Sesión permanente comprobada: $persistent. El agente quedó pausado.";Log.i("CampanaLocalQA","PASS native_lab_only=true persistent_session_tested=$persistent")}
            .onFailure{result.text="No se completó la prueba: "+it.message;Log.e("CampanaLocalQA","FAIL "+it.message,it)} }
    }
    override fun onResume(){super.onResume();(application as CampaignApp).graph.runtime.ensureStarted("local_qa_foreground")}
    private suspend fun until(label:String,predicate:()->Boolean) {
        withTimeout(10_000){while(!predicate())delay(50)}
        check(predicate()){label}
    }
    private suspend fun verify():Boolean {
        val g=(application as CampaignApp).graph;g.ready.await()
        until("Laboratorio visible"){window.decorView.hasWindowFocus() && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)}
        val wasActive=!g.paused.value
        until("Accesibilidad conectada"){PhoneAccessibilityService.instance!=null}
        if(wasActive)until("Halo permanente"){g.runtime.running.value && PhoneAccessibilityService.instance?.persistentShowing()==true}
        val service=checkNotNull(PhoneAccessibilityService.instance)
        var session=service.beginControl(localOnly=true)
        try {
            service.awaitControl(session)
            val first=service.observe();check(first.nodes.none{it.resource.endsWith("/control_recover")})
            // The accessibility target shares our process. Keep its UI thread free for the action.
            check(withContext(Dispatchers.Default){service.act(first.id,first.nodes.first{it.resource.endsWith("/sandbox_input")}.id,"write","Continuidad local")}){"El campo local no aceptó la escritura nativa."}
            session.close()
            check(!g.visualWorking.value && g.paused.value==!wasActive && service.persistentShowing()==wasActive){"El halo debe seguir el estado de la sesión."}
            val epoch=g.runtime.gate.epoch;delay(2200)
            if(wasActive)check(g.runtime.gate.permits(epoch) && service.persistentShowing())
            session=service.beginControl(localOnly=true);service.awaitControl(session)
            val token=service.controlToken()
            val state=service.observe();val button=state.nodes.first{it.resource.endsWith("/sandbox_send")}
            val rect=Rect(button.left,button.top,button.right,button.bottom)
            service.uncoverTarget(rect)
            val injected=CompletableDeferred<Boolean>();service.laboratoryTouch(rect,true){injected.complete(it)}
            check(withTimeout(5000){injected.await()})
            until("El gesto atraviesa el halo"){result.text.toString().contains("Recibido localmente: Continuidad local")}
            check(service.sameControl(token)){"Un gesto del agente no debe recuperar el control."}
            delay(1700)
            val pending=CoroutineScope(SupervisorJob()+Dispatchers.Default).launch{delay(60_000)}
            session.close();session=service.beginControl(localOnly=true,job=pending)
            service.awaitControl(session)
            val owner=CompletableDeferred<Boolean>();service.laboratoryTouch(rect,false){owner.complete(it)}
            withTimeout(5000){owner.await()}
            until("El toque externo recupera control"){g.paused.value && !g.runtime.running.value && !service.persistentShowing()}
            check(!g.runtime.gate.permits(epoch));withTimeout(5000){pending.join()};check(pending.isCancelled)
            check(runCatching{service.requireControl()}.isFailure)
            delay(2200);check(g.paused.value){"La pausa debe persistir."}
        } finally {session.close()}
        return wasActive
    }
    override fun onDestroy(){work.cancel();super.onDestroy()}
}
