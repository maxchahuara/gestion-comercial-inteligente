package org.campana.agent.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.campana.agent.data.*
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskEditor(campaign:Campaign,assets:List<Asset>,onBack:()->Unit,onSave:(AgentTask)->Unit,saving:Boolean=false,saveError:String="",onImport:()->Unit={},importedAssetId:String?=null) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var channel by rememberSaveable { mutableStateOf(campaign.channelList().firstOrNull()?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf("MESSAGE") }
    var instruction by rememberSaveable { mutableStateOf("") }
    var recipient by rememberSaveable { mutableStateOf("") }
    var assetId by rememberSaveable { mutableStateOf("") }
    val initialTime=remember { LocalDateTime.now().plusMinutes(30) }
    var later by rememberSaveable { mutableStateOf(false) }
    var day by rememberSaveable { mutableLongStateOf(initialTime.toLocalDate().toEpochDay()) }
    var hour by rememberSaveable { mutableIntStateOf(initialTime.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initialTime.minute) }
    var dateDialog by remember { mutableStateOf(false) };var timeDialog by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) };var error by remember { mutableStateOf("") };var discard by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    val instructionFocus=remember { FocusRequester() }
    val scrolls=listOf(rememberScrollState(),rememberScrollState())
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val recipients=campaign.recipients.lines().map { it.trim() }.filter { it.isNotBlank() }.distinctBy(::normalized)
    LaunchedEffect(importedAssetId,assets){if(importedAssetId!=null && assets.any { it.id==importedAssetId })assetId=importedAssetId}
    val future=remember(day,hour,minute){LocalDate.ofEpochDay(day).atTime(hour,minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}
    val dirty=instruction.isNotBlank() || recipient.isNotBlank() || assetId.isNotBlank() || later || kind!="MESSAGE" || channel!=campaign.channelList().firstOrNull()?.name.orEmpty()
    LaunchedEffect(instruction,recipient,channel,kind,assetId,later,day,hour,minute){error=""}
    fun leave(){if(!saving){if(dirty)discard=true else onBack()}}
    BackHandler { leave() }
    fun save(forceDraft:Boolean) {
        attempted=true
        error=when {
            channel !in campaign.channelList().map { it.name }->"Selecciona un canal de esta campaña."
            instruction.isBlank()->"Describe lo que debe preparar el agente."
            kind!="POST" && recipient.isBlank()->"Elige o escribe un destinatario."
            kind=="ATTACHMENT" && assetId.isBlank()->"Selecciona el archivo que quieres adjuntar."
            later && future<=System.currentTimeMillis()->"Elige una fecha y hora futuras."
            later && future-System.currentTimeMillis()>365L*24*60*60*1000->"Programa dentro de los próximos 365 días."
            !forceDraft && campaign.mode=="AUTO" && kind!="POST" && !campaign.allowsRecipient(recipient)->"Este nombre no está autorizado. Guarda un borrador y revisa las reglas."
            !forceDraft && campaign.mode=="AUTO" && kind=="POST" && (!campaign.allowPublishing || campaign.accountIdentity.isBlank())->"Falta autorizar publicaciones e identificar la cuenta. Guarda un borrador y revisa las reglas."
            else->""
        }
        if(error.isNotBlank()) { page=if(error.contains("fecha") || error.contains("365"))1 else 0;attempts++;return }
        focus.clearFocus();keyboard?.hide()
        onSave(AgentTask(campaignId=campaign.id,channel=channel,kind=kind,instruction=instruction.trim(),recipient=if(kind=="POST")"" else recipient.trim(),assetId=if(kind=="MESSAGE")"" else assetId,
            dueAt=if(later)future else System.currentTimeMillis(),status=if(forceDraft || campaign.mode!="AUTO")"DRAFT" else "QUEUED"))
    }
    LaunchedEffect(attempts){if(attempts>0 && error.isNotBlank()){withFrameNanos{};scrolls[page].animateScrollTo(0);if(instruction.isBlank() && page==0)instructionFocus.requestFocus()}}
    Scaffold(modifier=Modifier.imePadding(),topBar={CampTopAppBar(title={Text("Nueva tarea",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=::leave,enabled=!saving){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Volver")}})},bottomBar={
        Surface(tonalElevation=2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(campaign.mode=="AUTO")"Se intentará ejecutar dentro de tus reglas." else "Se guardará sin ejecutar. Podrás revisarla en Actividad.",style=MaterialTheme.typography.bodySmall)
                if(saveError.isNotBlank())Text(saveError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                CampButton(onClick={save(false)},enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text(if(saving)"Guardando…" else if(campaign.mode!="AUTO")"Guardar borrador" else if(later)"Programar tarea" else "Crear y ejecutar")
                }
                if(campaign.mode=="AUTO")CampTextButton(onClick={save(true)},enabled=!saving,modifier=Modifier.fillMaxWidth()){Text("Guardar como borrador")}
            }
        }
    }) { padding->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            Text(campaign.name,Modifier.padding(horizontal=20.dp,vertical=10.dp),style=MaterialTheme.typography.titleSmall)
            PrimaryTabRow(selectedTabIndex=page){listOf("Contenido","Cuándo").forEachIndexed { i,title->Tab(selected=page==i,onClick={page=i},text={Text(title,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)})}}
            Column(Modifier.weight(1f).verticalScroll(scrolls[page]).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
                if(page==0) {
                    ChoiceField("Canal",channel,campaign.channelList().map { it.name to it.title }){channel=it}
                    ChoiceField("Tipo de tarea",kind,listOf("MESSAGE" to "Mensaje","POST" to "Publicación","ATTACHMENT" to "Adjunto")){kind=it}
                    EditorField("Instrucción para el agente",instruction,{instruction=it.take(6000)},modifier=Modifier.focusRequester(instructionFocus),minLines=3,
                        error=if(attempted && instruction.isBlank())"Describe el contenido y el resultado que esperas." else "",help="Obligatorio. Hasta 6 000 caracteres.")
                    if(kind!="POST") {
                        if(recipients.isNotEmpty()) {
                            ChoiceField("Destinatario permitido",recipient,listOf("" to "Elegir destinatario")+recipients.map { it to it }){recipient=it}
                            Text("Usa el nombre que ya autorizaste. Comprueba los nombres repetidos en la app de destino.",style=MaterialTheme.typography.bodySmall)
                        } else EditorField("Destinatario propuesto",recipient,{recipient=it},single=true,help="Guarda un borrador. Autoriza este nombre en Reglas antes de enviar.")
                        if(attempted && recipient.isBlank())Text("Elige o escribe un destinatario.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    } else Text("Cuenta para publicar: "+campaign.accountIdentity.ifBlank { "sin configurar en Reglas" },style=MaterialTheme.typography.bodyMedium)
                    if(kind in setOf("POST","ATTACHMENT")) {
                        ChoiceField("Archivo de campaña",assetId,listOf("" to if(kind=="POST")"Sin archivo" else "Elegir archivo")+assets.map { it.id to it.name }){assetId=it}
                        if(attempted && kind=="ATTACHMENT" && assetId.isBlank())Text("Selecciona el archivo que quieres adjuntar.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        CampTextButton(onClick=onImport){Text("Importar archivo para esta tarea")}
                        if(assets.isEmpty())Text("Elige un archivo del teléfono. El contenido de esta tarea se conservará.",style=MaterialTheme.typography.bodySmall)
                    }
                } else {
                    SectionHeading("¿Cuándo debe empezar?")
                    ScheduleOption("Cuando el teléfono esté disponible",!later){later=false}
                    ScheduleOption("Más tarde",later){later=true}
                    if(later) {
                        CampOutlinedButton(onClick={dateDialog=true},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(10.dp));Text("Fecha: "+DateTimeFormatter.ofPattern("dd MMM yyyy",java.util.Locale.forLanguageTag("es-PE")).format(LocalDate.ofEpochDay(day)))}
                        CampOutlinedButton(onClick={timeDialog=true},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Icon(Icons.Outlined.Schedule,null);Spacer(Modifier.width(10.dp));Text("Hora prevista: %02d:%02d".format(hour,minute))}
                        Text("Se intentará a partir de "+displayDate(future)+", hora del teléfono.",style=MaterialTheme.typography.bodyLarge)
                    }
                    Text("Puede esperar internet, permisos, horario autorizado o desbloqueo. La hora prevista no garantiza una ejecución exacta.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if(dateDialog) {
        val state=rememberDatePickerState(initialSelectedDateMillis=LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={dateDialog=false},confirmButton={CampTextButton(onClick={state.selectedDateMillis?.let { day=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() };dateDialog=false}){Text("Elegir fecha")}},dismissButton={CampTextButton(onClick={dateDialog=false}){Text("Cancelar")}}){DatePicker(state)}
    }
    if(timeDialog) {
        val state=rememberTimePickerState(initialHour=hour,initialMinute=minute,is24Hour=DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(onDismissRequest={timeDialog=false},title={Text("Hora prevista")},text={TimeInput(state)},confirmButton={CampTextButton(onClick={hour=state.hour;minute=state.minute;timeDialog=false}){Text("Elegir hora")}},dismissButton={CampTextButton(onClick={timeDialog=false}){Text("Cancelar")}})
    }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("¿Descartar esta tarea?")},text={Text("Todavía no has guardado el contenido.")},confirmButton={CampTextButton(onClick=onBack){Text("Descartar")}},dismissButton={CampTextButton(onClick={discard=false}){Text("Seguir editando")}})
}

@Composable private fun ScheduleOption(label:String,selected:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected,role=Role.RadioButton,onClick=onClick).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        RadioButton(selected,onClick=null);Text(label)
    }
}
