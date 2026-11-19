package org.campana.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import org.campana.agent.data.*
import org.campana.agent.phone.SocialApps

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CampaignEditor(initial:Campaign,onBack:()->Unit,onSave:(Campaign)->Unit,initialPage:Int=0,saving:Boolean=false,saveError:String="") {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var page by rememberSaveable(initial.id) { mutableIntStateOf(initialPage) }
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var objective by rememberSaveable { mutableStateOf(initial.objective) }
    var offer by rememberSaveable { mutableStateOf(initial.offer) }
    var audience by rememberSaveable { mutableStateOf(initial.audience) }
    var tone by rememberSaveable { mutableStateOf(initial.tone) }
    var knowledge by rememberSaveable { mutableStateOf(initial.knowledge) }
    var channels by rememberSaveable { mutableStateOf(initial.channels) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    val automatic=mode!="DRAFT"
    val reactive=mode=="REACTIVE"
    var recipients by rememberSaveable { mutableStateOf(initial.recipients) }
    var identity by rememberSaveable { mutableStateOf(initial.accountIdentity) }
    var limit by rememberSaveable { mutableStateOf(initial.dailyLimit.toString()) }
    var quietStart by rememberSaveable { mutableStateOf(initial.quietStart.toString()) }
    var quietEnd by rememberSaveable { mutableStateOf(initial.quietEnd.toString()) }
    var silence by rememberSaveable { mutableStateOf(initial.quietStart!=initial.quietEnd) }
    var reply by rememberSaveable { mutableStateOf(initial.autoReply) }
    var publish by rememberSaveable { mutableStateOf(initial.allowPublishing) }
    var more by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var submitAttempt by remember { mutableIntStateOf(0) }
    var focusError by remember { mutableStateOf("") }
    val nameFocus=remember { FocusRequester() };val objectiveFocus=remember { FocusRequester() };val limitFocus=remember { FocusRequester() };val identityFocus=remember { FocusRequester() };val recipientsFocus=remember { FocusRequester() }
    val scrolls=listOf(rememberScrollState(),rememberScrollState(),rememberScrollState())
    val errors=buildMap {
        if(name.isBlank())put("name","Escribe un nombre para identificar la campaña.")
        if(objective.isBlank())put("objective","Añade el objetivo de esta campaña.")
        if(channels.isBlank())put("channels","Selecciona al menos un canal.")
        if(limit.toIntOrNull() !in 1..500)put("limit","Usa un límite entre 1 y 500.")
        if(silence && (quietStart.toIntOrNull() !in 0..23 || quietEnd.toIntOrNull() !in 0..23 || quietStart==quietEnd))put("quiet","Elige horas distintas o desactiva el horario de silencio.")
        if(automatic && !reactive && recipients.lines().all { it.isBlank() } && !(publish && identity.isNotBlank()))put("recipients","Añade un destinatario permitido o configura la identidad para publicar.")
        if(automatic && reply && "MESSENGER" in channels.split(",") && identity.isBlank())put("identity","Indica la fanpage de Messenger de esta campaña.")
        if(automatic && publish && identity.isBlank())put("identity","Escribe la identidad visible de la cuenta antes de autorizar publicaciones.")
        if(recipients.lines().any{it.trim()=="*"} && !(reactive && recipients.trim()=="*"))put("recipients","Cualquier remitente (*) solo se permite en Solo responder mensajes entrantes.")
        if(reactive && (!reply || publish))put("recipients","Solo respuestas necesita atención activada y publicaciones desactivadas.")
    }
    val dirty=name!=initial.name || objective!=initial.objective || offer!=initial.offer || audience!=initial.audience || tone!=initial.tone || knowledge!=initial.knowledge || channels!=initial.channels || mode!=initial.mode || recipients!=initial.recipients || identity!=initial.accountIdentity || limit!=initial.dailyLimit.toString() || quietStart!=initial.quietStart.toString() || quietEnd!=initial.quietEnd.toString() || silence!=(initial.quietStart!=initial.quietEnd) || reply!=initial.autoReply || publish!=initial.allowPublishing
    fun leave() { if(!saving) { if(dirty)discard=true else onBack() } }
    BackHandler { leave() }
    LaunchedEffect(submitAttempt) {
        if(submitAttempt>0 && focusError.isNotBlank()) {
            withFrameNanos { }
            when(focusError) { "name"->nameFocus.requestFocus();"objective"->objectiveFocus.requestFocus();"limit"->limitFocus.requestFocus();"identity"->identityFocus.requestFocus();"recipients"->recipientsFocus.requestFocus();else->scrolls[page].animateScrollTo(0) }
        }
    }
    Scaffold(modifier=Modifier.imePadding(),topBar={
        CampTopAppBar(title={Text(if(initial.name.isBlank())"Crear campaña" else "Editar campaña",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=::leave,enabled=!saving){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Volver")}})
    },bottomBar={
        Surface(tonalElevation=2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(reactive)"Solo responderá cuando reciba un mensaje directo autorizado." else if(automatic)"Se autorizarán acciones según estas reglas." else "Modo borradores: no enviará mensajes.",style=MaterialTheme.typography.bodySmall)
                if(saveError.isNotBlank())Text(saveError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                CampButton(onClick={
                    attempted=true
                    if(errors.isNotEmpty()) {
                        focusError=errors.keys.first();page=when(focusError){"name","objective"->0;"channels"->1;else->2};submitAttempt++
                    } else {
                        focus.clearFocus();keyboard?.hide()
                        onSave(initial.copy(name=name.trim(),objective=objective.trim(),offer=offer,audience=audience,tone=tone,knowledge=knowledge,channels=channels,
                            mode=mode,recipients=if(reactive)"*" else recipients.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n"),accountIdentity=identity.trim(),
                            dailyLimit=limit.toInt(),quietStart=if(silence)quietStart.toInt() else 0,quietEnd=if(silence)quietEnd.toInt() else 0,autoReply=reply,allowPublishing=publish))
                    }
                },enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    if(saving){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary);Spacer(Modifier.width(8.dp))}
                    Text(if(saving)"Guardando…" else if(automatic)"Guardar y autorizar" else "Guardar campaña")
                }
            }
        }
    }) { padding->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex=page) { listOf("Contexto","Canales","Reglas").forEachIndexed { i,title->
                Tab(selected=page==i,onClick={page=i},text={Text(title,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)})
            } }
            Column(Modifier.weight(1f).verticalScroll(scrolls[page]).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                if(page==0) {
                    SectionHeading("¿Qué quieres conseguir?",if(initial.name.isBlank())"Empieza con un nombre y un objetivo. Puedes completar el resto después." else "Actualiza el nombre y el objetivo. El contexto adicional es opcional.")
                    EditorField("Nombre de campaña",name,{name=it},single=true,error=if(attempted)errors["name"].orEmpty() else "",help="Obligatorio",modifier=Modifier.focusRequester(nameFocus))
                    EditorField("Objetivo",objective,{objective=it},minLines=2,error=if(attempted)errors["objective"].orEmpty() else "",help="Obligatorio. Describe el resultado que buscas.",modifier=Modifier.focusRequester(objectiveFocus))
                    CampOutlinedCard(onClick={more=!more},modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)){Text("Más contexto (opcional)",style=MaterialTheme.typography.titleSmall);Text("Oferta, público, tono e información aprobada",style=MaterialTheme.typography.bodyMedium)}
                            Icon(if(more)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(more)"Ocultar contexto adicional" else "Mostrar contexto adicional")
                        }
                    }
                    if(more) {
                        EditorField("Oferta, productos o servicios",offer,{offer=it},minLines=2)
                        EditorField("Público de la campaña",audience,{audience=it},minLines=2)
                        EditorField("Tono de comunicación",tone,{tone=it},single=true)
                        EditorField("Información y respuestas aprobadas",knowledge,{knowledge=it},minLines=4,help="Datos que el agente puede usar al redactar: horarios, precios y respuestas frecuentes.")
                    }
                } else if(page==1) {
                    SectionHeading("¿Dónde trabajarás?","Seleccionar un canal permite preparar tareas. No autoriza envíos.")
                    if(attempted && errors.containsKey("channels"))Text(errors.getValue("channels"),color=MaterialTheme.colorScheme.error)
                    val context=LocalContext.current
                    Channel.entries.forEach { channel->
                        val selected=channel.name in channels.split(",")
                        val installed=remember(channel) { SocialApps.installed(context,channel) }
                        Row(Modifier.fillMaxWidth().toggleable(selected,role=Role.Checkbox,onValueChange={checked->channels=channels.split(",").filter { it.isNotBlank() }.toMutableSet().apply { if(checked)add(channel.name) else remove(channel.name) }.joinToString(",")}).heightIn(min=72.dp).padding(vertical=12.dp),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Checkbox(selected,onCheckedChange=null)
                            Column(Modifier.weight(1f)) { Text(channel.title,style=MaterialTheme.typography.titleMedium);Text(if(installed)"Instalada · compatibilidad en pruebas" else "No instalada o deshabilitada. Puedes planificar borradores.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        HorizontalDivider()
                    }
                    Text("Los envíos usan las aplicaciones instaladas con tus sesiones abiertas. Cada función depende de su versión e interfaz.",style=MaterialTheme.typography.bodyMedium)
                } else {
                    SectionHeading("Tú decides qué puede hacer","Empieza con borradores. Autoriza acciones cuando hayas revisado destinos y límites.")
                    ModeOption("Solo borradores","Prepara contenido sin enviar mensajes ni publicar.",mode=="DRAFT"){mode="DRAFT"}
                    ModeOption("Solo responder mensajes entrantes","Atiende mensajes directos nuevos. No inicia conversaciones ni publica.",reactive){mode="REACTIVE";reply=true;publish=false}
                    ModeOption("Automático según mis reglas","Puede enviar y actuar dentro de las reglas que guardes.",mode=="AUTO"){mode="AUTO"}
                    HorizontalDivider()
                    if(reactive)Text("Responde a cualquier persona que escriba directamente a la campaña. No utiliza una lista de contactos.")
                    else EditorField("Nombres permitidos",recipients,{recipients=it},minLines=2,help="Uno por línea, tal como aparece en la conversación. Verifica los nombres repetidos en la app de destino.",error=if(attempted)errors["recipients"].orEmpty() else "",modifier=Modifier.focusRequester(recipientsFocus))
                    EditorField("Límite diario de envíos iniciados",limit,{limit=it.filter(Char::isDigit).take(3)},single=true,keyboardType=KeyboardType.Number,help="De 1 a 500 envíos iniciados o publicaciones. No limita las respuestas entrantes de Solo responder.",error=if(attempted)errors["limit"].orEmpty() else "",modifier=Modifier.focusRequester(limitFocus))
                    SwitchRow("Silencio para envíos iniciados","No limita la atención continua de Solo responder mensajes entrantes.",silence,{silence=it})
                    if(silence) {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            ChoiceField("Desde",quietStart,(0..23).map { it.toString() to "%02d:00".format(it) },Modifier.weight(1f)){quietStart=it}
                            ChoiceField("Hasta",quietEnd,(0..23).map { it.toString() to "%02d:00".format(it) },Modifier.weight(1f)){quietEnd=it}
                        }
                        Text("Hora local del teléfono. Puede incluir la noche.",style=MaterialTheme.typography.bodySmall)
                        if(attempted && errors.containsKey("quiet"))Text(errors.getValue("quiet"),color=MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider()
                    SectionHeading("Acciones permitidas","Estas opciones solo actúan en modo automático.")
                    if(reactive)Text("La llegada de un mensaje directo activa la respuesta. La atención continúa hasta que recuperes el control.")
                    else {
                        SwitchRow("Responder mensajes entrantes","Solo a los nombres permitidos y cuando la notificación permita responder.",reply,{reply=it})
                        SwitchRow("Permitir publicaciones","Autoriza publicar en la cuenta que identifiques abajo.",publish,{publish=it})
                    }
                    if(publish || (reply && "MESSENGER" in channels.split(",")))EditorField(if(!publish)"Fanpage de Messenger" else "Identidad visible de la cuenta",identity,{identity=it},single=true,help=if(reply && "MESSENGER" in channels.split(","))"Nombre exacto de la página de campaña. El perfil personal administrador no autoriza responder desde su bandeja." else "Nombre o usuario que se debe comprobar antes de publicar.",error=if(attempted)errors["identity"].orEmpty() else "",modifier=Modifier.focusRequester(identityFocus))
                    Text("Puedes pausar el agente en cualquier momento. Las reglas se comprueban antes de cada acción.",style=MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("¿Descartar los cambios?")},text={Text("Los cambios de esta campaña todavía no se han guardado.")},
        confirmButton={CampTextButton(onClick={discard=false;onBack()}){Text("Descartar")}},dismissButton={CampTextButton(onClick={discard=false}){Text("Seguir editando")}})
}

@Composable internal fun EditorField(label:String,value:String,onChange:(String)->Unit,modifier:Modifier=Modifier,single:Boolean=false,minLines:Int=2,help:String="",error:String="",keyboardType:KeyboardType=KeyboardType.Text) {
    CampTextField(value,{onChange(it.take(if(single)200 else 30000))},modifier.fillMaxWidth(),label={Text(label,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},singleLine=single,minLines=if(single)1 else minLines,maxLines=if(single)1 else 8,
        isError=error.isNotBlank(),keyboardOptions=KeyboardOptions(keyboardType=keyboardType),supportingText=if(error.isNotBlank() || help.isNotBlank()) { {Text(error.ifBlank { help })} } else null)
}

@Composable internal fun SwitchRow(title:String,description:String,value:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value,role=Role.Switch,onValueChange=onChange).heightIn(min=64.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(value,onCheckedChange=null)
    }
}

@Composable private fun ModeOption(title:String,description:String,selected:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected,role=Role.RadioButton,onClick=onClick).heightIn(min=72.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        RadioButton(selected,onClick=null)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable internal fun ChoiceField(label:String,value:String,options:List<Pair<String,String>>,modifier:Modifier=Modifier,onChange:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            CampOutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text(options.firstOrNull { it.first==value }?.second ?: "Elegir",Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null)
            }
            DropdownMenu(expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=320.dp)) {
                options.forEach { (id,title)->DropdownMenuItem(text={Text(title)},onClick={onChange(id);expanded=false}) }
            }
        }
    }
}
