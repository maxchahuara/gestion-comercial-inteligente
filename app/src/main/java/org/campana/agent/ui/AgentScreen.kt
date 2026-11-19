package org.campana.agent.ui

import android.content.Intent
import android.net.Uri

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.campana.agent.AppGraph
import org.campana.agent.agent.CampaignImageGeneration
import org.campana.agent.agent.GatunaGuide
import org.campana.agent.data.*
import org.campana.agent.net.safeError
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun displayDate(time:Long):String=DateTimeFormatter.ofPattern("dd MMM · HH:mm",java.util.Locale.forLanguageTag("es-PE"))
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Composable internal fun AgentScreen(g:AppGraph,campaigns:List<Campaign>,active:Campaign?,history:List<ChatMessage>,busy:Boolean,visual:Boolean,paused:Boolean,
    conversationId:String,draft:String,onDraft:(String)->Unit,onRestoreDraft:(String)->Unit,onSelect:(String)->Unit,onCreate:()->Unit,onManualCreate:()->Unit,onRules:()->Unit,onSettings:()->Unit,onResources:()->Unit,onImport:()->Unit,onActivity:()->Unit) {
    val clipboard=LocalClipboardManager.current
    var revision by remember { mutableIntStateOf(0) }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_RESUME)revision++ }
        owner.lifecycle.addObserver(observer);onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val model by g.model.collectAsState()
    val incomingWake by g.incomingWake.collectAsState()
    val connected=remember(revision,model) { runCatching { g.auth.vault.active()?.accessToken?.isNotBlank()==true }.getOrDefault(false) }
    val sending by g.conversations.submitting.collectAsState()
    var options by remember { mutableStateOf(false) }
    var dedicating by remember(active?.id){mutableStateOf(false)}
    val listState=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val atEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index.let { it==null || it>=listState.layoutInfo.totalItemsCount-2 } } }
    var previousCount by remember(active?.id) { mutableIntStateOf(0) }
    var newMessages by remember(active?.id) { mutableStateOf(false) }
    LaunchedEffect(active?.id,history.size) {
        if(history.isNotEmpty()) {
            if(previousCount==0 || atEnd || sending)listState.animateScrollToItem(history.lastIndex)
            else if(history.size>previousCount)newMessages=true
        }
        previousCount=history.size
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
      val headerLimit=maxHeight*.55f
      Column(Modifier.fillMaxSize()) {
        // Keep the existing order; overflow scrolls instead of hiding the chat at large font sizes.
        Column(Modifier.fillMaxWidth().heightIn(max=headerLimit).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if(campaigns.isNotEmpty())CampaignSelector(campaigns,active,onSelect,onCreate=if(!busy && !sending)onCreate else null)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(active==null)"Nueva campaña · empieza conversando" else if(active.mode=="REACTIVE")"Solo responder mensajes entrantes" else if(active.mode=="AUTO")"Automático según tus reglas" else "Borradores · sin envíos automáticos",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    Box {
                        IconButton(onClick={options=true}){Icon(Icons.Outlined.MoreVert,"Opciones de la conversación")}
                        DropdownMenu(options,onDismissRequest={options=false}) {
                            DropdownMenuItem(text={Text("Nueva campaña por chat")},leadingIcon={Icon(Icons.Outlined.Add,null)},onClick={options=false;onCreate()},enabled=!busy && !sending)
                            if(active!=null) {
                                DropdownMenuItem(text={Text("Generar imagen")},onClick={options=false;onDraft("/imagen ")},enabled=!busy && !sending)
                                if (GatunaGuide.applies(active)) DropdownMenuItem(text={Text("Vista previa del menú gatuno")},onClick={
                                    options=false
                                    g.scope.launch { g.conversations.send(active.id,"/menu-gatuno",false) }
                                },enabled=!busy && !sending)
                                DropdownMenuItem(text={Text("Ver actividad")},onClick={options=false;onActivity()})
                                DropdownMenuItem(text={Text("Ver recursos")},leadingIcon={Icon(Icons.Outlined.FolderOpen,null)},onClick={options=false;onResources()})
                                DropdownMenuItem(text={Text("Configuración avanzada")},onClick={options=false;onRules()})
                            }
                            DropdownMenuItem(text={Text("Crear con formulario")},onClick={options=false;onManualCreate()})
                        }
                    }
                }
                if(active?.mode=="REACTIVE" && active.autoReply) {
                    val anyone=active.isPublicInbox()
                    SwitchRow("Activar al recibir mensajes",if(anyone)"Responde a cualquiera que escriba directamente. Pausar detiene la atención." else "Atiende a los contactos autorizados aunque la app esté en segundo plano. Pausar detiene la atención.",incomingWake,g::setIncomingWake)
                    if(anyone && active.channels=="WHATSAPP")Text("WhatsApp dedicado a esta campaña · sin lista de contactos",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                }
                if(active!=null && !active.isPublicInbox()) {
                    CampOutlinedButton(onClick={
                        dedicating=true
                        g.scope.launch {
                            try{g.conversations.useDedicatedWhatsApp(active.id)}catch(e:Exception){g.status.value=safeError(e)}finally{dedicating=false}
                        }
                    },enabled=!busy && !sending && !dedicating,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text(if(dedicating)"Preparando atención…" else "Usar WhatsApp como número de campaña")
                    }
                    Text("Responde a cualquiera que escriba directamente por WhatsApp, según tus horarios y límites.",style=MaterialTheme.typography.bodySmall)
                }
            }
            if(!connected || model.isBlank()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=20.dp,vertical=12.dp)) {
                    Text(if(!connected)"Conecta ChatGPT para recibir respuestas." else "Elige un modelo para continuar.",style=MaterialTheme.typography.bodyMedium)
                    CampTextButton(onClick=onSettings){Text("Abrir ajustes de ChatGPT")}
                }
            } else if(paused) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Control humano. Puedes configurar la campaña por chat; Reanudar devuelve el control al agente.",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                if(history.isEmpty())item {
                    EmptyState(if(active==null)"Cuéntame qué quieres conseguir" else "¿Qué hacemos con esta campaña?",
                        if(active==null)"Dime qué ofreces y qué resultado buscas. Guardaré la campaña mientras conversamos, primero en borradores." else "Pídeme contenido, cambios o un resumen de tareas. También puedes adjuntar archivos aquí.")
                    val prompts=if(active==null)listOf("Quiero crear una campaña para mi negocio") else listOf("Prepara un plan de contenido para esta semana","¿Cómo van las tareas de esta campaña?")
                    prompts.forEach { prompt->
                        CampOutlinedButton(onClick={onDraft(prompt)},modifier=Modifier.fillMaxWidth().padding(top=8.dp).heightIn(min=48.dp)){Text(prompt)}
                    }
                }
                itemsIndexed(history,key={_,message->message.id}) { index,message->
                    if(message.role=="rules_proposal" && active!=null) {
                        RulesProposalCard(g,active,message,busy || sending)
                        return@itemsIndexed
                    }
                    val error=message.role=="error"
                    val human=message.role=="user"
                    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(human)Alignment.End else Alignment.Start) {
                        Column(Modifier.fillMaxWidth(if(human).9f else 1f)
                            .then(if(human || error)Modifier.background(if(error)MaterialTheme.colorScheme.errorContainer else LocalCampaignVisualColors.current.selected,MaterialTheme.shapes.large).padding(14.dp) else Modifier),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(when(message.role){"user"->"Tú";"error"->"No se completó la respuesta";"notice"->"Aviso";else->"Agente"},Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold,
                                    color=when {error->MaterialTheme.colorScheme.onErrorContainer;human->MaterialTheme.colorScheme.onSurface;message.role=="notice"->LocalCampaignVisualColors.current.neutral;else->MaterialTheme.colorScheme.tertiary})
                                if(!human)IconButton(onClick={clipboard.setText(AnnotatedString(plainMessage(message.text)));g.status.value="Texto copiado."}) { Icon(Icons.Outlined.ContentCopy,"Copiar respuesta",modifier=Modifier.size(18.dp)) }
                            }
                            SelectionContainer { ReadableMessage(message.text) }
                            Text(displayDate(message.createdAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(error) {
                                Text("Revisa Actividad antes de reenviar: una tarea podría haberse creado.",style=MaterialTheme.typography.bodySmall)
                                CampTextButton(onClick={history.take(index).lastOrNull { it.role=="user" }?.let { onDraft(it.text) }}){Text("Recuperar texto para editar")}
                                CampTextButton(onClick=onActivity){Text("Ver actividad")}
                            }
                        }
                    }
                }
                if(busy || sending)item {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        Text(if(paused)"Finalizando la pausa…" else if(visual)"Operando una aplicación…" else "Preparando respuesta…",style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if(newMessages)CampTextButton(onClick={newMessages=false;scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount-1).coerceAtLeast(0)) }},modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Ver mensajes nuevos")}
            val imageBrief = draft.removePrefix("/imagen ").trim()
            val imageRequest = active != null && draft.startsWith("/imagen ") && imageBrief.length in 1..2600
            if (imageRequest) CampTextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(CampaignImageGeneration.visualBrief(imageBrief)))
                    runCatching {
                        g.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onSuccess { g.status.value = "Encargo copiado. Pégalo en ChatGPT y vuelve para adjuntar la imagen que descargues." }
                        .onFailure { g.status.value = "Encargo copiado. Abre ChatGPT para generar la imagen." }
                },
                enabled = !busy && !sending,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Copiar encargo y abrir ChatGPT") }
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(active!=null)IconButton(onClick=onImport,enabled=!sending && !busy,modifier=Modifier.size(48.dp)){Icon(Icons.Outlined.AttachFile,"Adjuntar archivo a esta campaña",tint=if(!sending && !busy)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)}
                CampTextField(draft,{onDraft(it.take(12000))},Modifier.weight(1f),placeholder={Text(if(active==null)"Cuéntame tu idea" else "Habla con tu agente")},minLines=1,maxLines=4)
                FilledIconButton(onClick={
                    val input=draft.trim();val campaignId=conversationId;val creating=active==null
                    onDraft("")
                    g.scope.launch {
                        try {
                            val started=System.currentTimeMillis()
                            g.conversations.send(campaignId,input,creating)
                            val response=g.dao.history(campaignId).firstOrNull()
                            if(response?.role=="error" && response.createdAt>=started)onRestoreDraft(input)
                        } catch(e:Exception) { onRestoreDraft(input);g.status.value=safeError(e) }
                    }
                },enabled=draft.isNotBlank() && (imageRequest || (connected && model.isNotBlank())) && !busy && !sending,modifier=Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send,"Enviar instrucción")
                }
            }
      }
    }
}
