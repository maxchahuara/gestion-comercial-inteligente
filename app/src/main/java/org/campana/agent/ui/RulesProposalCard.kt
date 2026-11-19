package org.campana.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.campana.agent.AppGraph
import org.campana.agent.agent.RulesProposal
import org.campana.agent.data.Campaign
import org.campana.agent.data.ChatMessage
import org.campana.agent.net.json
import org.campana.agent.net.safeError

@Composable internal fun RulesProposalCard(g:AppGraph,campaign:Campaign,message:ChatMessage,busy:Boolean) {
    val proposal=remember(message.text) { runCatching { json.decodeFromString<RulesProposal>(message.text).also { it.proposed.validate() } }.getOrNull() }
    var review by remember(message.id) { mutableStateOf(false) }
    var working by remember(message.id) { mutableStateOf(false) }
    var error by remember(message.id) { mutableStateOf("") }
    val paused by g.paused.collectAsState()
    fun resolve(approve:Boolean) {
        working=true;error=""
        g.scope.launch {
            runCatching { g.conversations.resolve(message.id,campaign.id,approve) }
                .onSuccess { review=false;g.status.value=it }.onFailure { error=safeError(it) }
            working=false
        }
    }
    val tone=LocalCampaignVisualColors.current
    CampOutlinedCard(Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=tone.warningBg),
        border=androidx.compose.foundation.BorderStroke(CobaltStroke.Default,tone.warning)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Reglas propuestas",style=MaterialTheme.typography.titleMedium,color=tone.warning)
            if(proposal==null)Text("No se puede leer esta propuesta. Descártala y pide una nueva al agente.")
            else {
                Text("Aún sin aplicar. "+if(campaign.mode!="DRAFT")"Siguen vigentes tus reglas actuales." else "Sigues en borradores hasta que confirmes.")
                CampButton(onClick={review=true},enabled=!busy && !working,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Revisar propuesta")}
            }
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            CampTextButton(onClick={resolve(false)},enabled=!working && !busy){Text("Descartar propuesta")}
        }
    }
    if(review && proposal!=null)AlertDialog(onDismissRequest={if(!working)review=false},
        title={Text(if(proposal.proposed.mode!="DRAFT")"Revisar autorización" else "Revisar reglas de borradores")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(campaign.name,style=MaterialTheme.typography.titleMedium)
            Text("Modo actual: "+org.campana.agent.data.campaignModeLabel(campaign.mode))
            proposal.proposed.summary().lines().forEach { Text(it) }
            Text("No ejecuta borradores existentes. "+if(paused)"El agente seguirá pausado hasta que pulses Reanudar." else "Las nuevas tareas usarán estas reglas.")
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={CampTextButton(onClick={resolve(true)},enabled=!working && !busy){Text(if(working)"Guardando…" else if(proposal.proposed.mode!="DRAFT")"Autorizar estas reglas" else "Guardar en borradores")}},
        dismissButton={CampTextButton(onClick={review=false},enabled=!working){Text("Volver al chat")}})
}
