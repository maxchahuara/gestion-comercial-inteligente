package org.campana.agent.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import kotlinx.serialization.encodeToString
import org.campana.agent.net.json
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.campana.agent.*
import org.campana.agent.net.safeError
import org.campana.agent.data.*
import org.campana.agent.jobs.TaskWorker
import org.campana.agent.phone.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CampaignRoot(g:AppGraph,requestNotices:()->Unit) = CampaignTheme {
    val campaigns by g.dao.watchCampaigns().collectAsStateWithLifecycle(emptyList())
    val tasks by g.dao.watchTasks().collectAsStateWithLifecycle(emptyList())
    val messages by g.dao.watchMessages().collectAsStateWithLifecycle(emptyList())
    val assets by g.dao.watchAssets().collectAsStateWithLifecycle(emptyList())
    val leads by g.dao.watchLeads().collectAsStateWithLifecycle(emptyList())
    val receipts by g.dao.watchReceipts().collectAsStateWithLifecycle(emptyList())
    val activeId by g.activeCampaign.collectAsStateWithLifecycle()
    val paused by g.paused.collectAsStateWithLifecycle()
    val busy by g.busy.collectAsStateWithLifecycle()
    val visual by g.visualWorking.collectAsStateWithLifecycle()
    val listening by SocialNotificationService.connected.collectAsStateWithLifecycle()
    val scriptReady by g.postLinks.ready.collectAsStateWithLifecycle()
    val runtimePhase by g.runtime.phase.collectAsStateWithLifecycle()
    val incomingWake by g.incomingWake.collectAsStateWithLifecycle()
    val status by g.status.collectAsStateWithLifecycle()
    val active=campaigns.firstOrNull { it.id==activeId }
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var newConversation by rememberSaveable { mutableStateOf(false) }
    var newConversationId by rememberSaveable { mutableStateOf(newId()) }
    var settingsPage by rememberSaveable { mutableStateOf("index") }
    var activitySection by rememberSaveable { mutableIntStateOf(0) }
    val campaignSaver=Saver<Campaign?,String>(save={json.encodeToString(it)},restore={json.decodeFromString<Campaign?>(it)})
    var editor by rememberSaveable(stateSaver=campaignSaver) { mutableStateOf<Campaign?>(null) }
    var editorPage by rememberSaveable { mutableIntStateOf(0) }
    var editorReturn by rememberSaveable { mutableStateOf("campaign") }
    var taskDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTask=tasks.firstOrNull { it.id==taskDetailId }
    var savingTask by remember { mutableStateOf(false) }
    var taskError by remember { mutableStateOf("") }
    var taskImportedAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    val leadSaver=Saver<Lead?,String>(save={json.encodeToString(it)},restore={json.decodeFromString<Lead?>(it)})
    var leadEditor by rememberSaveable(stateSaver=leadSaver) { mutableStateOf<Lead?>(null) }
    var savingLead by remember { mutableStateOf(false) }
    var leadError by remember { mutableStateOf("") }
    var savingEditor by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailResources by rememberSaveable { mutableStateOf(false) }
    var chatDrafts by rememberSaveable { mutableStateOf(mapOf<String,String>()) }
    val chatCampaign=if(newConversation)null else active
    val chatId=chatCampaign?.id ?: newConversationId
    fun selectConversation(id:String) { newConversation=false;g.selectCampaign(id) }
    fun startNewConversation() {
        if(busy || g.conversations.submitting.value)return
        if(campaigns.any { it.id==newConversationId })newConversationId=newId()
        newConversation=true;tab=1;detailId=null;taskDetailId=null
    }
    LaunchedEffect(activeId,newConversationId) { if(activeId==newConversationId)newConversation=false }
    val detailCampaign=campaigns.firstOrNull { it.id==detailId }
    val snackbar=remember { SnackbarHostState() }
    var noticeDetail by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var newTask by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(status,editor!=null,newTask,leadEditor!=null) {
        if(status.isNotBlank() && editor==null && !newTask && leadEditor==null) {
            val captured=status
            val result=snackbar.showSnackbar(captured.take(150)+(if(captured.length>150)"…" else ""),actionLabel="Ver",duration=SnackbarDuration.Long)
            if(result==SnackbarResult.ActionPerformed)noticeDetail=captured
            if(g.status.value==captured)g.status.value=""
        }
    }
    var backupId by rememberSaveable { mutableStateOf<String?>(null) }
    val backupCampaign=campaigns.firstOrNull { it.id==backupId }
    var importId by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val saveCopy=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val name=pendingExport;pendingExport=null
        if(uri!=null && name!=null)g.scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    require(name.matches(Regex("campana-[a-zA-Z0-9-]+\\.campana")))
                    val folder=java.io.File(g.context.cacheDir,"exports");val file=java.io.File(folder,name)
                    require(file.canonicalFile.parentFile==folder.canonicalFile)
                    checkNotNull(g.context.contentResolver.openOutputStream(uri,"wt")).use { output->file.inputStream().use{it.copyTo(output)} }
                }
            }.onSuccess { g.status.value="Copia protegida guardada en la ubicación elegida." }
                .onFailure { g.status.value="No se pudo guardar la copia. Vuelve a exportarla y elige otra ubicación." }
        } else if(uri==null)g.status.value="No se guardó la copia. Puedes exportarla de nuevo."
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target=importId
        if(uri!=null && target!=null) g.scope.launch {
            runCatching { checkNotNull(g.dao.campaign(target));g.assets.import(target,uri) }.onSuccess { if(newTask){taskImportedAssetId=it.id;taskError=""}else g.status.value="Archivo importado: "+it.name }
                .onFailure { if(newTask)taskError=safeError(it) else g.status.value=safeError(it) }
        }
    }
    val restorePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->if(uri!=null)restoreUri=uri }
    LaunchedEffect(campaigns,activeId) {
        if(active==null && campaigns.isNotEmpty()) {
            // Room may not have emitted a just-created campaign yet. Never replace an id that exists.
            val exists=activeId.isNotBlank() && g.dao.campaign(activeId)!=null
            if(!exists && g.activeCampaign.value==activeId)g.selectCampaign(campaigns.first().id)
        }
    }
    BackHandler(enabled=editor==null && !newTask && leadEditor==null && (taskDetailId!=null || detailId!=null || (tab==3 && settingsPage!="index") || tab!=1)) { when { tab==3 && settingsPage!="index"->settingsPage="index";taskDetailId!=null->taskDetailId=null;detailId!=null->detailId=null;else->tab=1 } }
    if(editor!=null) {
        CampaignEditor(editor!!,initialPage=editorPage,saving=savingEditor,saveError=editorError,onBack={editor=null;editorError=""},onSave={ value ->
            if(!savingEditor) {
                savingEditor=true
                editorError=""
                g.scope.launch {
                    runCatching {
                        val creating=g.dao.campaign(value.id)==null
                        g.dao.save(value.copy(updatedAt=System.currentTimeMillis()))
                        if(creating || editorReturn=="agent")selectConversation(value.id)
                    }
                        .onSuccess {
                            editor=null
                            if(editorReturn=="campaign"){tab=0;detailId=value.id;detailResources=false}else detailId=null
                            g.status.value="Campaña guardada. "+when(value.mode){"AUTO"->"Se aplican tus reglas de autonomía.";"REACTIVE"->"Solo responderá mensajes directos nuevos según tus reglas.";else->"Los envíos siguen en modo borradores."}
                        }
                        .onFailure { editorError=safeError(it) }
                    savingEditor=false
                }
            }
        })
    } else if(leadEditor!=null) {
        val lead=leadEditor!!;val campaign=campaigns.firstOrNull { it.id==lead.campaignId }
        if(campaign!=null)LeadEditor(campaign,lead,onBack={leadEditor=null;leadError=""},saving=savingLead,error=leadError,onSave={value->
            if(!savingLead){savingLead=true;leadError="";g.scope.launch {
                runCatching { checkNotNull(g.dao.campaign(value.campaignId));g.dao.save(value) }.onSuccess { leadEditor=null;tab=2;activitySection=2;g.status.value="Interesado guardado en esta campaña." }.onFailure { leadError=safeError(it) };savingLead=false
            }}
        })
    } else if(newTask && active!=null) {
        TaskEditor(active,assets.filter { it.campaignId==active.id },onBack={newTask=false;taskError=""},saving=savingTask,saveError=taskError,
            onImport={importId=active.id;importer.launch(arrayOf("*/*"))},importedAssetId=taskImportedAssetId,onSave={task->
                if(!savingTask){savingTask=true;taskError="";g.scope.launch {
                    runCatching { g.enqueue(task) }.onSuccess { newTask=false;tab=2;activitySection=0;taskDetailId=task.id;g.status.value=if(task.status=="DRAFT")"Borrador guardado sin ejecutar." else "Tarea programada según tus reglas." }.onFailure { taskError=safeError(it) };savingTask=false
                }}
            })
    } else {
        Scaffold(
            topBar={
                CampTopAppBar(hasSubtitle=true,title={Column {
                    Text(if(tab==3)settingsTitle(settingsPage) else if(tab==2 && selectedTask!=null)"Tarea" else if(tab==0 && detailCampaign!=null)"Campaña" else listOf("Campañas","Agente","Actividad","Ajustes")[tab],fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(if(paused)"Control humano · agente pausado" else if(!scriptReady && active?.mode=="REACTIVE")"Preparando guion y enlace" else runtimePhase,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,
                        color=if(!paused && (busy || visual))LocalCampaignVisualColors.current.info else MaterialTheme.colorScheme.onSurfaceVariant)
                }},navigationIcon={
                    if(tab==3 && settingsPage!="index")IconButton(onClick={settingsPage="index"}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Volver a ajustes") }
                    else if(tab==2 && taskDetailId!=null)IconButton(onClick={taskDetailId=null}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Volver a tareas") }
                    else if(tab==0 && detailId!=null)IconButton(onClick={detailId=null}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Volver a campañas") }
                },actions={
                    CampTextButton(onClick={if(paused)g.resumeByOwner() else g.pause(true)}) {
                        Icon(if(paused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,contentDescription=null)
                        Text(if(paused)"Reanudar" else "Pausar")
                    }
                    if(tab==0 && detailId==null)Box {
                        IconButton(onClick={menu=true}) { Icon(Icons.Outlined.MoreVert,"Opciones de campañas") }
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("Restaurar una copia")},onClick={menu=false;restorePicker.launch(arrayOf("*/*"))})
                            DropdownMenuItem(text={Text("Crear con formulario")},onClick={menu=false;editorReturn="campaign";editorPage=0;editorError="";editor=Campaign()})
                        }
                    }
                })
            },
            bottomBar={
                NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                    listOf("Campañas","Agente","Actividad","Ajustes").forEachIndexed { index,title ->
                        val icon=listOf(Icons.Outlined.Campaign,Icons.Outlined.ChatBubbleOutline,Icons.Outlined.Checklist,Icons.Outlined.Settings)[index]
                        NavigationBarItem(selected=tab==index,onClick={tab=index;detailId=null;taskDetailId=null},icon={Icon(icon,contentDescription=null)},label={Text(title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)},
                            colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=MaterialTheme.colorScheme.primary,indicatorColor=LocalCampaignVisualColors.current.selected,
                                unselectedIconColor=MaterialTheme.colorScheme.onSurfaceVariant,unselectedTextColor=MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
            ,snackbarHost={SnackbarHost(snackbar)},floatingActionButton={
                if(tab==0 && detailId==null && campaigns.isNotEmpty())ExtendedFloatingActionButton(onClick={startNewConversation()},icon={Icon(Icons.Outlined.Add,null)},text={Text("Crear campaña")})
            }
        ) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                when(tab) {
                    0->{
                        if(detailCampaign==null)CampaignIndex(campaigns,activeId,onOpen={detailResources=false;detailId=it},onCreate={startNewConversation()},onRestore={restorePicker.launch(arrayOf("*/*"))})
                        else CampaignDetail(detailCampaign,assets.filter { it.campaignId==detailCampaign.id },showResources=detailResources,
                            onChat={selectConversation(detailCampaign.id);detailId=null;tab=1},
                            onEdit={editorReturn="campaign";editorError="";editorPage=it;editor=detailCampaign},
                            onImport={importId=detailCampaign.id;importer.launch(arrayOf("*/*"))},
                            onExport={backupId=detailCampaign.id},
                            onOpenAsset={asset->runCatching { g.context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(g.assets.uri(asset),asset.mime)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { g.status.value="Instala una aplicación que abra este tipo de archivo y vuelve a intentarlo." }})
                    }
                    1->AgentScreen(g,campaigns,chatCampaign,messages.filter { it.campaignId==chatId },busy,visual,paused,
                        conversationId=chatId,draft=chatDrafts[chatId].orEmpty(),onDraft={chatDrafts=chatDrafts+(chatId to it)},
                        onRestoreDraft={if(chatDrafts[chatId].isNullOrBlank())chatDrafts=chatDrafts+(chatId to it)},
                        onSelect={selectConversation(it)},onCreate={startNewConversation()},
                        onManualCreate={editorReturn="campaign";editorPage=0;editorError="";editor=Campaign()},
                        onRules={if(active!=null){editorReturn="agent";editorError="";editorPage=2;editor=active}},onSettings={settingsPage="account";tab=3},
                        onResources={detailResources=true;detailId=chatId;tab=0},onImport={importId=chatId;importer.launch(arrayOf("*/*"))},onActivity={tab=2})
                    2->{
                        val taskCampaign=selectedTask?.let { t->campaigns.firstOrNull { it.id==t.campaignId } }
                        if(selectedTask!=null && taskCampaign!=null)TaskDetail(g,selectedTask,taskCampaign,assets.firstOrNull { it.id==selectedTask.assetId },
                            onRules={editorReturn="task";editorError="";editorPage=2;editor=taskCampaign},onSettings={settingsPage="index";tab=3;taskDetailId=null})
                        else ActivityScreen(campaigns,active,tasks,receipts,leads,section=activitySection,onSection={activitySection=it},onSelect={selectConversation(it)},
                            onCreate={startNewConversation()},
                            onNewTask={taskError="";taskImportedAssetId=null;newTask=true},onTask={taskDetailId=it},
                            onLead={lead->if(active!=null){leadError="";leadEditor=lead ?: Lead(campaignId=active.id,name="",channel=active.channelList().firstOrNull()?.name ?: "WHATSAPP",contact="",notes="")}})
                    }
                    3->SettingsScreen(g,settingsPage,onPage={settingsPage=it},requestNotices)
                }
            }
        }
    }
    if(noticeDetail.isNotBlank())AlertDialog(onDismissRequest={noticeDetail=""},title={Text("Aviso del agente")},text={Text(noticeDetail)},confirmButton={CampTextButton(onClick={noticeDetail=""}){Text("Entendido")}})

    if(backupCampaign!=null)ExportCopyDialog(g,backupCampaign,onDismiss={backupId=null},onGenerated={file->
        pendingExport=file.name;backupId=null
        saveCopy.launch("Campana-"+java.time.LocalDate.now().toString()+".campana")
    })
    if(restoreUri!=null)RestoreCopyDialog(g,restoreUri!!,onDismiss={restoreUri=null},onChangeFile={restorePicker.launch(arrayOf("*/*"))},onRestored={campaign->
        selectConversation(campaign.id);detailResources=false;detailId=campaign.id;tab=0;restoreUri=null
        g.status.value="Campaña restaurada como una copia nueva en modo borradores."
    })
}
