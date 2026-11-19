package org.campana.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.campana.agent.data.Asset
import org.campana.agent.data.Campaign

@Composable internal fun CampaignIndex(campaigns:List<Campaign>,selectedId:String,onOpen:(String)->Unit,onCreate:()->Unit,onRestore:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=16.dp,bottom=96.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(campaigns.isEmpty())item {
            EmptyState("Crea tu primera campaña","Cuéntale al agente qué ofreces y qué quieres conseguir. La campaña se guarda mientras conversan, primero en borradores.","Crear campaña",onCreate)
            CampTextButton(onClick=onRestore,modifier=Modifier.heightIn(min=48.dp)){Text("Restaurar una copia")}
        }
        items(campaigns,key={it.id}) { c->
            CampOutlinedCard(onClick={onOpen(c.id)},modifier=Modifier.fillMaxWidth(),
                colors=CardDefaults.outlinedCardColors(containerColor=if(c.id==selectedId)LocalCampaignVisualColors.current.selected else MaterialTheme.colorScheme.surface),
                border=BorderStroke(1.dp,if(c.id==selectedId)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        if(c.id==selectedId)Text("Seleccionada",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
                        Text(c.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(c.objective.ifBlank { "Añade el objetivo de esta campaña." },style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(c.channelList().joinToString(" · "){it.title},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        ModeLabel(c)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward,"Ver campaña",modifier=Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable internal fun CampaignDetail(campaign:Campaign,assets:List<Asset>,onChat:()->Unit,onEdit:(Int)->Unit,onImport:()->Unit,onExport:()->Unit,onOpenAsset:(Asset)->Unit,showResources:Boolean=false) {
    var contextExpanded by remember(campaign.id) { mutableStateOf(false) }
    val listState=rememberLazyListState()
    LaunchedEffect(campaign.id,showResources){if(showResources)listState.scrollToItem(3)}
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(campaign.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                ModeLabel(campaign)
                Text(when(campaign.mode){"AUTO"->"El agente puede actuar dentro de los destinatarios, horarios y límites que autorizaste.";"REACTIVE"->"Solo atiende mensajes directos nuevos de los remitentes autorizados. No inicia conversaciones ni publica.";else->"El agente prepara contenido. Los envíos necesitan tu autorización."},
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SectionHeading("Objetivo")
                Text(campaign.objective)
                Text(campaign.channelList().joinToString(" · "){it.title},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column {
                CampButton(onClick=onChat,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(Icons.Outlined.ChatBubbleOutline,null);Spacer(Modifier.width(8.dp));Text("Hablar con el agente") }
                ActionRow("Editar contexto","Objetivo, oferta, público y conocimiento",Icons.Outlined.Edit){onEdit(0)}
                HorizontalDivider()
                ActionRow("Revisar reglas","Canales, destinatarios y autorización de envíos",Icons.Outlined.Tune){onEdit(2)}
                CampTextButton(onClick={contextExpanded=!contextExpanded},modifier=Modifier.heightIn(min=48.dp)) { Text(if(contextExpanded)"Ocultar contexto" else "Ver contexto completo") }
                if(contextExpanded)Column(Modifier.padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    listOf("Oferta" to campaign.offer,"Público" to campaign.audience,"Tono" to campaign.tone,"Información aprobada" to campaign.knowledge).forEach { (label,value)->
                        Column { Text(label,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold);Text(value.ifBlank { "Sin completar" },style=MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SectionHeading("Recursos","Imágenes y documentos de esta campaña.")
                CampOutlinedButton(onClick=onImport,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(Icons.Outlined.FileUpload,null);Spacer(Modifier.width(8.dp));Text("Importar archivo") }
                if(assets.isEmpty())Text("Todavía no hay archivos. Añade información para que el agente pueda usarla.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(assets,key={it.id}) { asset->
            ActionRow(asset.name,if(asset.extractedText.isNotBlank())"Disponible para el agente" else when {
                asset.mime.startsWith("image/")->"Imagen de campaña";asset.mime=="application/pdf"->"Documento PDF";asset.mime.startsWith("video/")->"Video";else->"Archivo de campaña"
            },if(asset.mime.startsWith("image/"))Icons.Outlined.Image else Icons.Outlined.Description){onOpenAsset(asset)}
        }
        item {
            SectionHeading("Copias")
            ActionRow("Exportar copia","Guarda los datos y archivos con contraseña.",Icons.Outlined.SaveAlt,onExport)
        }
    }
}
