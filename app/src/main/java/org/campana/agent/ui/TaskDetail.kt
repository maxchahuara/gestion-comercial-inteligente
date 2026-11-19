package org.campana.agent.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.campana.agent.AppGraph
import org.campana.agent.data.*
import org.campana.agent.jobs.TaskWorker
import org.campana.agent.net.safeError
import org.campana.agent.phone.SocialApps

@Composable internal fun TaskDetail(g:AppGraph,task:AgentTask,campaign:Campaign,asset:Asset?,onRules:()->Unit,onSettings:()->Unit) {
    var cancel by remember { mutableStateOf(false) };var execute by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val canRun=task.status in setOf("DRAFT","WAITING_OWNER","WAITING_UNLOCK","WAITING_NETWORK","WAITING_PERMISSION")
    val canCancel=task.status in setOf("DRAFT","QUEUED","WAITING_OWNER","WAITING_UNLOCK","WAITING_NETWORK","WAITING_PERMISSION")
    fun openApp() {
        runCatching {
            val channel=Channel.valueOf(task.channel)
            val intent=SocialApps.launchIntent(g.context,channel) ?: error("La aplicación no está disponible. Revísala en Ajustes.")
            g.pause(true)
            g.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { g.status.value=safeError(it) }
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Text(campaign.name,style=MaterialTheme.typography.titleSmall);Spacer(Modifier.height(12.dp));Text(taskTitle(task),style=MaterialTheme.typography.headlineSmall) }
        item { TaskStateLabel(task);Spacer(Modifier.height(10.dp));Text(taskExplanation(task)) }
        item { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SectionHeading("Destino y momento")
            Text("Canal: "+channelName(task.channel))
            Text(if(task.kind=="POST")"Cuenta: "+campaign.accountIdentity.ifBlank { "Sin configurar" } else "Destinatario: "+task.recipient)
            if(asset!=null)Text("Archivo: "+asset.name)
            Text((if(task.status=="DRAFT")"Fecha propuesta: " else "Hora prevista: ")+displayDate(task.dueAt))
            Text("Última actualización: "+displayDate(task.updatedAt),style=MaterialTheme.typography.bodySmall)
        } }
        item { SectionHeading("Instrucción");Spacer(Modifier.height(8.dp));SelectionContainer { ReadableMessage(if(task.kind=="READ_POST")runCatching{val target=org.campana.agent.net.json.decodeFromString<org.campana.agent.agent.PublishedPostTarget>(task.instruction);"Localizar la publicación de ${target.account}, guardar su enlace y ensayar las respuestas sin enviarlas.\n\n"+target.caption}.getOrDefault("Verificar el enlace de la publicación de esta campaña.") else task.instruction) } }
        if(task.detail.isNotBlank())item { SectionHeading("Resultado disponible");Spacer(Modifier.height(8.dp));SelectionContainer { ReadableMessage(task.detail) } }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(task.status=="UNCERTAIN") {
                    Text("Esta tarea no se reenvía automáticamente. Abrir la app pausa el agente para que puedas comprobar el resultado.")
                    CampButton(onClick=::openApp,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Abrir "+channelName(task.channel))}
                } else if(canRun) {
                    if(task.kind=="READ_POST") {
                        Text("Al reanudar, el agente preparará el enlace y el guion por sí mismo. No necesita que operes la red ni copies enlaces.")
                    } else if(campaign.mode=="REACTIVE") {
                        Text("Solo se responde a un mensaje directo nuevo y verificable. Espera un mensaje nuevo; este modo no ejecuta envíos manuales ni publicaciones.")
                    } else if(campaign.mode!="AUTO") {
                        Text("La campaña está en modo borradores. Revisa y autoriza sus reglas antes de ejecutar.")
                        CampButton(onClick=onRules,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Revisar reglas")}
                    } else CampButton(onClick={execute=true},enabled=!working,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Revisar y ejecutar")}
                    CampOutlinedButton(onClick=onSettings,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Revisar ajustes de la app")}
                } else if(task.status=="SUBMITTED") {
                    Text("La acción se presentó a la aplicación. Comprueba su resultado antes de crear otro intento.")
                    CampOutlinedButton(onClick=::openApp,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Revisar en "+channelName(task.channel))}
                }
                if(canCancel)CampTextButton(onClick={cancel=true},enabled=!working,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Cancelar tarea pendiente")}
            }
        }
    }
    if(cancel)AlertDialog(onDismissRequest={cancel=false},title={Text("¿Cancelar la tarea pendiente?")},text={Text("Se detendrán sus intentos pendientes. Esto no borra ni deshace mensajes presentados a una app.")},
        confirmButton={CampTextButton(onClick={cancel=false;working=true;g.scope.launch { runCatching { g.dao.cancelPending(task.id,System.currentTimeMillis()) }.onSuccess { g.status.value=if(it==1)"Tarea pendiente cancelada." else "La tarea cambió o ya comenzó. Revisa su estado y usa Pausar si es necesario." }.onFailure{g.status.value=safeError(it)};working=false }}){Text("Cancelar tarea")}},dismissButton={CampTextButton(onClick={cancel=false}){Text("Volver")}})
    if(execute)AlertDialog(onDismissRequest={execute=false},title={Text("Revisar antes de ejecutar")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(taskKind(task.kind)+" por "+channelName(task.channel));Text(if(task.kind=="POST")campaign.accountIdentity else task.recipient)
        if(asset!=null)Text("Archivo: "+asset.name)
        Text("Se aplicarán las reglas actuales y la hora prevista. Si el agente está pausado, necesitarás reanudarlo.")
    }},confirmButton={CampTextButton(onClick={execute=false;working=true;g.scope.launch {
        runCatching {
            if(g.dao.queueReviewed(task.id,System.currentTimeMillis())==1){TaskWorker.schedule(g.context,task.copy(status="QUEUED"),reviewed=true);g.status.value="Tarea programada según tus reglas."}
            else g.status.value="La tarea cambió. Revisa su estado antes de continuar."
        }.onFailure{g.status.value=safeError(it)};working=false
    }}){Text("Ejecutar tarea")}},dismissButton={CampTextButton(onClick={execute=false}){Text("Volver")}})
}
