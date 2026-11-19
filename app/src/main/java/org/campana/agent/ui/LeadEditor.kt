package org.campana.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import org.campana.agent.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LeadEditor(campaign:Campaign,initial:Lead,onBack:()->Unit,onSave:(Lead)->Unit,saving:Boolean,error:String) {
    var name by rememberSaveable(initial.id){mutableStateOf(initial.name)}
    var channel by rememberSaveable{mutableStateOf(initial.channel)}
    var contact by rememberSaveable{mutableStateOf(initial.contact)}
    var notes by rememberSaveable{mutableStateOf(initial.notes)}
    var stage by rememberSaveable{mutableStateOf(initial.stage)}
    var attempted by remember{mutableStateOf(false)};var discard by remember{mutableStateOf(false)}
    val dirty=name!=initial.name || channel!=initial.channel || contact!=initial.contact || notes!=initial.notes || stage!=initial.stage
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    fun leave(){if(!saving){if(dirty)discard=true else onBack()}}
    BackHandler { leave() }
    Scaffold(modifier=Modifier.imePadding(),topBar={CampTopAppBar(title={Text(if(initial.name.isBlank())"Registrar interesado" else "Editar interesado",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick=::leave,enabled=!saving){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Volver")}})},bottomBar={
        Surface(tonalElevation=2.dp){Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Se guardará solo en esta campaña.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            CampButton(onClick={attempted=true;if(name.isNotBlank()){focus.clearFocus();keyboard?.hide();onSave(initial.copy(name=name.trim(),channel=channel,contact=contact.trim(),notes=notes,stage=stage))}},enabled=!saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(saving)"Guardando…" else "Guardar interesado")}
        }}
    }) { padding->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text(campaign.name,style=MaterialTheme.typography.titleSmall)
            Text("Registrar un contacto no autoriza envíos.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
            EditorField("Nombre",name,{name=it},single=true,help="Obligatorio",error=if(attempted && name.isBlank())"Escribe el nombre del interesado." else "")
            val channels=(campaign.channelList().map { it.name to it.title }+listOf(initial.channel to channelName(initial.channel))).distinctBy{it.first}
            ChoiceField("Canal",channel,channels){channel=it}
            EditorField("Número o usuario (opcional)",contact,{contact=it},single=true)
            EditorField("Notas (opcional)",notes,{notes=it.take(6000)},minLines=3)
            ChoiceField("Estado",stage,(listOf("Nuevo","En seguimiento","Cliente","Descartado")+initial.stage).distinct().map{it to it}){stage=it}
        }
    }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("¿Descartar los cambios?")},text={Text("Los datos del interesado todavía no están guardados.")},confirmButton={CampTextButton(onClick=onBack){Text("Descartar")}},dismissButton={CampTextButton(onClick={discard=false}){Text("Seguir editando")}})
}
