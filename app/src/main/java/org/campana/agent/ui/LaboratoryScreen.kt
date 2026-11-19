package org.campana.agent.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button as NativeButton
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.campana.agent.R
import org.campana.agent.net.safeError
import org.campana.agent.phone.PhoneAccessibilityService
import org.campana.agent.phone.ScreenState

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LaboratoryScreen(onBack:()->Unit)=CampaignTheme {
    var inputText by rememberSaveable{mutableStateOf("")}
    var result by rememberSaveable{mutableStateOf("Sin acciones")}
    var probeResult by rememberSaveable{mutableStateOf("")}
    var probing by remember{mutableStateOf(false)}
    val connected by PhoneAccessibilityService.connected.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope();val scroll=rememberScrollState()
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current;val context=LocalContext.current
    val colors=MaterialTheme.colorScheme
    val foreground=colors.onSurface.toArgb();val secondary=colors.onSurfaceVariant.toArgb();val primary=colors.primary.toArgb();val background=colors.surface.toArgb();val outline=colors.outline.toArgb()
    fun shape(stroke:Int)=GradientDrawable().apply {setColor(background);cornerRadius=12*context.resources.displayMetrics.density;setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1),stroke)}
    Scaffold(modifier=Modifier.imePadding(),topBar={CampTopAppBar(title={Text("Laboratorio",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Volver a ayuda")}})}) { padding->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(scroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            SectionHeading("Prueba local de pantalla","Comprueba lectura, escritura y pulsación con Accesibilidad.")
            CampButton(onClick={
                probing=true;probeResult="";focus.clearFocus();keyboard?.hide()
                scope.launch {
                    var control:PhoneAccessibilityService.ControlSession?=null
                    try { probeResult=runCatching {
                        val service=checkNotNull(PhoneAccessibilityService.instance){"Activa Accesibilidad para la app y vuelve a intentarlo."}
                        // Let the owner's initiating tap finish before reserving the screen.
                        delay(250)
                        control=service.beginControl(localOnly=true,job=currentCoroutineContext()[Job])
                        service.awaitControl(control!!)
                        scroll.animateScrollTo(scroll.maxValue);delay(180)
                        val message="Prueba local de Accesibilidad · "+(android.os.SystemClock.uptimeMillis()%100000)
                        suspend fun observedWhere(matches:(ScreenState)->Boolean):ScreenState {
                            val deadline=android.os.SystemClock.uptimeMillis()+3000
                            do {
                                val state=service.observe()
                                if(matches(state))return state
                                delay(100)
                            } while(android.os.SystemClock.uptimeMillis()<deadline)
                            error("No se pudo observar el resultado de la prueba. Comprueba la zona de prueba y vuelve a intentarlo.")
                        }
                        val first=service.observe()
                        val input=first.nodes.first { it.resource.endsWith("/sandbox_input") }
                        service.act(first.id,input.id,"write",message)
                        val second=observedWhere{it.nodes.any{node->node.resource.endsWith("/sandbox_input") && node.text==message}}
                        val button=second.nodes.first { it.resource.endsWith("/sandbox_send") }
                        service.act(second.id,button.id,"click","")
                        observedWhere{it.nodes.any{node->node.resource.endsWith("/sandbox_status") && node.text.contains("Recibido localmente: "+message)}}
                        "Lectura, escritura y pulsación comprobadas en esta pantalla."
                    }.getOrElse{if(it is CancellationException)"Prueba pausada. Tú tienes el control." else safeError(it)}
                    } finally {withContext(NonCancellable+Dispatchers.Main){control?.close();probing=false}}
                }
            },enabled=connected && !probing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(probing)"Comprobando…" else "Probar Accesibilidad")}
            if(!connected) {
                Text("Activa el servicio de esta app en Accesibilidad para ejecutar la prueba.",style=MaterialTheme.typography.bodyMedium)
                CampTextButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Abrir Accesibilidad de Android")}
            }
            Text("La secuencia automática es local. No usa ChatGPT ni envía mensajes a redes.",style=MaterialTheme.typography.bodyMedium)
            if(probing)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(probeResult.isNotBlank())Text(probeResult,style=MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            SectionHeading("Zona de prueba")
            AndroidView(factory={TextView(it).apply{id=R.id.sandbox_recipient;text="Contacto de prueba local";textSize=16f}},update={it.setTextColor(foreground)},modifier=Modifier.fillMaxWidth())
            AndroidView(factory={ctx->EditText(ctx).apply{
                id=R.id.sandbox_input;hint="Texto de prueba";textSize=16f;minLines=2;maxLines=4
                val p=(14*resources.displayMetrics.density).toInt();setPadding(p,p,p,p)
                inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){inputText=s?.toString().orEmpty()};override fun afterTextChanged(s:Editable?){} })
            }},update={view->
                if(view.text.toString()!=inputText)view.setText(inputText)
                view.setTextColor(foreground);view.setHintTextColor(secondary);view.background=shape(outline)
            },modifier=Modifier.fillMaxWidth().heightIn(min=72.dp))
            AndroidView(factory={NativeButton(it).apply{id=R.id.sandbox_send;text="Enviar prueba local";isAllCaps=false;textSize=16f}},update={view->
                view.setTextColor(primary);view.background=shape(primary);view.setOnClickListener{result="Recibido localmente: "+inputText}
            },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp))
            AndroidView(factory={TextView(it).apply{id=R.id.sandbox_status;textSize=16f;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}},update={it.text=result;it.setTextColor(foreground)},modifier=Modifier.fillMaxWidth())
            Text("El botón manual solo actualiza este resultado local. La comprobación automática usa el servicio de Accesibilidad.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
