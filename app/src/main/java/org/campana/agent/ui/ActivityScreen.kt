package org.campana.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.campana.agent.data.*
import org.campana.agent.net.json
import kotlinx.serialization.json.*

internal fun channelName(value:String)=Channel.entries.firstOrNull { it.name==value }?.title ?: value
internal fun taskKind(value:String)=when(value){"MESSAGE"->"Mensaje";"REPLY"->"Respuesta";"POST"->"Publicación";"ATTACHMENT"->"Adjunto";"READ_POST"->"Verificar enlace y guion";"READ_ACCOUNT"->"Verificar fanpage";"PREPARE_INBOX"->"Preparar bandeja de campaña";else->"Tarea"}
internal fun taskTitle(task:AgentTask)=taskKind(task.kind)+(if(task.recipient.isNotBlank())" · "+task.recipient else "")
internal fun taskState(value:String)=when(value){
    "DRAFT"->"Borrador";"QUEUED"->"Programada";"RUNNING"->"En ejecución";"SUBMITTING"->"Presentando acción";
    "WAITING_OWNER","WAITING_PERMISSION"->"Necesita revisión";"WAITING_NETWORK"->"Esperando internet";"WAITING_UNLOCK"->"Esperando desbloqueo";
    "SUBMITTED"->"Presentada a la app";"UNCERTAIN"->"Resultado incierto";"CANCELLED"->"Cancelada";"COMPLETED"->"Verificada";else->value
}
internal fun taskExplanation(task:AgentTask)=when(task.status){
    "DRAFT"->"No está programada para enviar."
    "SUBMITTED"->if(task.kind=="POST")"Publicación sin confirmar." else "Entrega sin confirmar."
    "UNCERTAIN"->"Comprueba el resultado en la app antes de repetir."
    "WAITING_UNLOCK"->"La acción visual necesita el teléfono desbloqueado."
    "WAITING_NETWORK"->"Se retomará cuando haya internet y se cumplan tus reglas."
    "WAITING_OWNER","WAITING_PERMISSION"->"Abre los detalles para revisar el motivo."
    "CANCELLED"->"No se iniciarán nuevos intentos de esta tarea."
    "COMPLETED"->"Enlace guardado y conversación ensayada, sin enviar los mensajes del ensayo."
    else->"Hora prevista: "+displayDate(task.dueAt)
}

@Composable internal fun TaskStateLabel(task:AgentTask) {
    val warning=task.status in setOf("UNCERTAIN","WAITING_OWNER","WAITING_PERMISSION")
    val tone=LocalCampaignVisualColors.current
    val (foreground,background)=when {
        warning->tone.warning to tone.warningBg
        task.status in setOf("FAILED","ERROR")->MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
        task.status=="COMPLETED"->tone.success to tone.successBg
        task.status in setOf("RUNNING","SUBMITTING","SUBMITTED","QUEUED")->tone.info to tone.infoBg
        else->tone.neutral to tone.neutralBg
    }
    Surface(color=background,contentColor=foreground,shape=MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal=9.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            if(warning)Icon(Icons.Outlined.WarningAmber,null,Modifier.size(16.dp),tint=foreground)
            Text(taskState(task.status),style=MaterialTheme.typography.labelMedium,color=foreground)
        }
    }
}

internal data class ReceiptSummary(val title:String,val description:String,val error:Boolean)
internal fun receiptSummary(receipt:Receipt,task:AgentTask?):ReceiptSummary {
    val detail=runCatching { json.parseToJsonElement(receipt.detail).jsonObject }.getOrNull()
    val result=(detail?.get("result") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val error=runCatching { json.parseToJsonElement(result).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    val name=receipt.action.substringAfterLast('.')
    val label=when(name){
        "campaign_read"->"Consultó la campaña";"update_brief"->"Actualizó el contexto";"search_knowledge"->"Consultó la información";
        "create_image"->"Preparó una imagen";"generate_campaign_image"->"Generó una imagen";"read_image"->"Analizó una imagen";"create_task"->"Preparó una tarea";
        "gatuna_handoff"->"El remitente pidió atención humana"
        "gatuna_subscription"->"Preferencia de aviso gatuno"
        "gatuna_opening"->"Aviso de apertura presentado"
        "remember_lead"->"Registró un interesado";"open_channel"->"Abrió una aplicación";"observe_screen","capture_screen"->"Revisó la pantalla";
        "screen_action"->"Interacción con la aplicación";"share_attachment"->"Preparó un adjunto";"external_submit"->"Intento de envío registrado";else->"Acción registrada"
    }
    if(error!=null)return ReceiptSummary("No se completó la acción",error,true)
    if(name=="external_submit")return ReceiptSummary(label,task?.let { taskState(it.status)+". "+taskExplanation(it) } ?: "Se registró un intento; la entrega no está confirmada.",task?.status=="UNCERTAIN")
    return ReceiptSummary(label,when(name){
        "create_image","generate_campaign_image"->"La imagen está disponible en Recursos."
        "gatuna_handoff"->task?.let{"Revisa la consulta de ${it.recipient} en ${it.channel}."} ?: "Revisa la conversación de esta tarea."
        "gatuna_subscription"->if(receipt.status=="ACTIVE")"El remitente activó el aviso para su chat." else "El remitente canceló el aviso."
        "gatuna_opening"->"La app presentó el aviso al responder. La entrega no está confirmada."
        "create_task"->"Consulta Tareas para revisar su estado y destino."
        "screen_action"->"Se registró la respuesta de un control; no prueba entrega."
        "remember_lead"->"Información guardada en este teléfono."
        "share_attachment"->"El selector de la app recibió el archivo; todavía requiere verificar el destino."
        else->"Resultado registrado en este teléfono."
    },false)
}

@Composable internal fun ActivityScreen(campaigns:List<Campaign>,active:Campaign?,tasks:List<AgentTask>,receipts:List<Receipt>,leads:List<Lead>,
    section:Int,onSection:(Int)->Unit,onSelect:(String)->Unit,onCreate:()->Unit,onNewTask:()->Unit,onTask:(String)->Unit,onLead:(Lead?)->Unit) {
    Column(Modifier.fillMaxSize()) {
        if(active==null) {
            Column(Modifier.padding(20.dp)){EmptyState("Elige una campaña","Las tareas, los registros y los interesados se organizan por campaña.","Crear campaña",onCreate)}
        } else {
            Box(Modifier.padding(horizontal=20.dp,vertical=10.dp)){CampaignSelector(campaigns,active,onSelect)}
            PrimaryScrollableTabRow(selectedTabIndex=section,edgePadding=0.dp) {
                listOf("Tareas","Registro","Interesados").forEachIndexed { index,title->Tab(selected=section==index,onClick={onSection(index)},text={Text(title,maxLines=1,softWrap=false,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)})}
            }
            val selectedTasks=tasks.filter { it.campaignId==active.id }
            val selectedReceipts=receipts.filter { it.campaignId==active.id }
            val selectedLeads=leads.filter { it.campaignId==active.id }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(section==0) {
                    item { CampButton(onClick=onNewTask,modifier=Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text("Nueva tarea")} }
                    if(selectedTasks.isEmpty())item { EmptyState("Todavía no hay tareas","Prepara un mensaje, un adjunto o una publicación. Podrás revisar el destino y las reglas antes de ejecutar.") }
                    items(selectedTasks,key={it.id}) { task->
                        CampOutlinedCard(onClick={onTask(task.id)},modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                Text(taskTitle(task),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(channelName(task.channel),style=MaterialTheme.typography.labelMedium)
                                Text(displayDate(task.updatedAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                TaskStateLabel(task)
                                Text(task.instruction,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(taskExplanation(task),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if(task.status=="UNCERTAIN")"Revisar resultado" else "Ver detalles",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else if(section==1) {
                    item { Text("Acciones registradas en este teléfono.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    if(selectedReceipts.isEmpty())item { EmptyState("Aún no hay registros","Aquí verás qué hizo el agente y la evidencia disponible de cada resultado.") }
                    items(selectedReceipts,key={it.id}) { receipt->
                        ReceiptRow(receipt,selectedTasks.firstOrNull { it.id==receipt.taskId },onTask)
                    }
                } else {
                    item { CampButton(onClick={onLead(null)},modifier=Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text("Registrar interesado")} }
                    if(selectedLeads.isEmpty())item { EmptyState("Aún no hay interesados","Registra un contacto para conservar sus datos y organizar el seguimiento de esta campaña. No autoriza envíos.") }
                    items(selectedLeads,key={it.id}) { lead->
                        CampOutlinedCard(onClick={onLead(lead)},modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                Text(lead.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                Text(channelName(lead.channel)+lead.contact.takeIf { it.isNotBlank() }?.let { " · "+it }.orEmpty(),style=MaterialTheme.typography.bodyMedium)
                                Text(lead.stage,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                                if(lead.notes.isNotBlank())Text(lead.notes,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                                Text("Ver y editar",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ReceiptRow(receipt:Receipt,task:AgentTask?,onTask:(String)->Unit) {
    var expanded by rememberSaveable(receipt.id) { mutableStateOf(false) }
    val summary=remember(receipt,task?.status){receiptSummary(receipt,task)}
    Column(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(if(summary.error)Icons.Outlined.WarningAmber else Icons.Outlined.Info,null,tint=when {task?.status=="UNCERTAIN"->LocalCampaignVisualColors.current.warning;summary.error->MaterialTheme.colorScheme.error;receipt.action=="external_submit"->LocalCampaignVisualColors.current.info;else->MaterialTheme.colorScheme.onSurfaceVariant})
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(summary.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                Text(summary.description,style=MaterialTheme.typography.bodyMedium)
                Text(displayDate(receipt.createdAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(task!=null)CampTextButton(onClick={onTask(task.id)}){Text("Ver tarea")}
                CampTextButton(onClick={expanded=!expanded}) { Text(if(expanded)"Ocultar detalles técnicos" else "Detalles técnicos");Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null) }
                if(expanded)SelectionContainer {
                    Text(receipt.action+" · "+receipt.status+"\n\n"+receipt.detail,style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)
                }
            }
        }
        HorizontalDivider(Modifier.padding(top=10.dp))
    }
}
