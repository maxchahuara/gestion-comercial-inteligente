package org.campana.agent.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.campana.agent.AppGraph
import org.campana.agent.BuildConfig
import org.campana.agent.auth.LocalDiagnostics
import org.campana.agent.jobs.IndependenceWorker
import org.campana.agent.net.safeError
import org.campana.agent.phone.SandboxActivity

@Composable internal fun HelpSettings(g:AppGraph,revision:Int,onAccount:()->Unit) {
    val status by g.status.collectAsStateWithLifecycle()
    val model by g.model.collectAsStateWithLifecycle()
    val runtimePhase by g.runtime.phase.collectAsStateWithLifecycle()
    val runtimeReport by g.runtime.report.collectAsStateWithLifecycle()
    var advanced by rememberSaveable{mutableStateOf(false)}
    var rsaBusy by remember{mutableStateOf(false)};var rsaResult by remember{mutableStateOf(g.prefs.getString("rsa_result","").orEmpty())}
    val workFlow=remember{WorkManager.getInstance(g.context).getWorkInfosForUniqueWorkFlow("independence-check")}
    val jobs by workFlow.collectAsStateWithLifecycle(emptyList())
    val pending=jobs.any{!it.state.isFinished}
    val running=jobs.any{it.state==WorkInfo.State.RUNNING}
    val configured=remember(revision,model){runCatching{g.auth.vault.active()?.accessToken?.isNotBlank()==true}.getOrDefault(false)}
    val result=remember(status,revision,jobs){g.prefs.getString("independence_result","").orEmpty()}
    val time=remember(status,revision,jobs){g.prefs.getLong("independence_time",0)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("Comprueba el funcionamiento sin enviar mensajes a tus redes.")
        SectionHeading("Prueba de pantalla","Comprueba lectura, escritura y pulsación en un laboratorio local.")
        CampOutlinedButton(onClick={g.context.startActivity(Intent(g.context,SandboxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))},modifier=Modifier.heightIn(min=48.dp)){Text("Abrir laboratorio")}
        HorizontalDivider()
        SectionHeading("Sesión permanente",runtimePhase)
        Text(runtimeReport.ifBlank{g.prefs.getString("runtime_report","").orEmpty().ifBlank{"La observación de continuidad comienza al reanudar."}},style=MaterialTheme.typography.bodyMedium)
        Text("El registro de 24 horas se genera en este celular. Un toque humano pausa la sesión; los contadores de mensajes presentados no certifican entrega.",style=MaterialTheme.typography.bodySmall)
        val power=g.context.getSystemService(android.os.PowerManager::class.java)
        if(!power.isIgnoringBatteryOptimizations(g.context.packageName)) {
            Text("Para atender con la pantalla apagada, revisa que Android no restrinja el uso de batería de la app.",style=MaterialTheme.typography.bodySmall)
            CampTextButton(onClick={g.context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}){Text("Revisar uso de batería")}
        }
        SectionHeading("Conexión con ChatGPT","Consulta breve desde el teléfono. Usa el acceso autorizado de tu cuenta.")
        if(!configured || model.isBlank()) {
            Text("Configura una cuenta y elige un modelo antes de comprobar la conexión.",style=MaterialTheme.typography.bodyMedium)
            CampTextButton(onClick=onAccount){Text("Revisar cuenta y modelo")}
        }
        CampButton(onClick={IndependenceWorker.schedule(g.context)},enabled=configured && model.isNotBlank() && !pending,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(pending)"Comprobación en proceso…" else "Comprobar conexión")}
        if(pending) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if(running)"Consultando ChatGPT desde este teléfono…" else "Programada. Esperando disponibilidad de Android e internet.",style=MaterialTheme.typography.bodyMedium)
        }
        if(result.isNotBlank()) {
            SectionHeading("Último resultado",if(time>0)displayDate(time) else "")
            Text(result,style=MaterialTheme.typography.bodyMedium)
        } else Text("El resultado quedará guardado aquí. Puedes volver más tarde.",style=MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        CampOutlinedButton(onClick={advanced=!advanced},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
            Text("Diagnóstico avanzado",Modifier.weight(1f));Icon(if(advanced)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)
        }
        if(advanced) {
            Text("Prueba local del verificador de firmas. No usa tu cuenta ni certifica la seguridad de todo el teléfono.",style=MaterialTheme.typography.bodyMedium)
            CampOutlinedButton(onClick={rsaBusy=true;g.scope.launch{
                rsaResult=runCatching{withContext(Dispatchers.Default){check(LocalDiagnostics.verifyRsa(g.context))};"Verificador de firmas: prueba local correcta."}.getOrElse{safeError(it)}
                g.prefs.edit().putString("rsa_result",rsaResult).apply();rsaBusy=false
            }},enabled=!rsaBusy,modifier=Modifier.heightIn(min=48.dp)){Text(if(rsaBusy)"Comprobando…" else "Comprobar firma digital")}
            if(rsaResult.isNotBlank())Text(rsaResult,style=MaterialTheme.typography.bodyMedium)
        }
        SectionHeading("Qué puede preparar el agente","Planes de contenido, redacción, imágenes con plantilla, tareas y seguimiento de interesados. Las acciones dependen de las reglas y de la compatibilidad de cada app.")
        Text("Estas comprobaciones no prueban compatibilidad con todas las redes ni continuidad permanente.",style=MaterialTheme.typography.bodySmall)
        Text("Versión "+BuildConfig.VERSION_NAME+" · Código abierto · Sin servidor propio",style=MaterialTheme.typography.labelSmall)
    }
}
