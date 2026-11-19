package org.campana.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.campana.agent.data.Campaign
import org.campana.agent.data.campaignModeLabel

@Composable internal fun SectionHeading(title:String,description:String="") {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.semantics { heading() })
        if(description.isNotBlank())Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun EmptyState(title:String,description:String,action:String="",onAction:()->Unit={}) {
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
        Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(action.isNotBlank())CampButton(onClick=onAction,modifier=Modifier.padding(top=6.dp).heightIn(min=48.dp)) { Text(action) }
    }
}

@Composable internal fun ModeLabel(campaign:Campaign) {
    val tone=LocalCampaignVisualColors.current
    Surface(color=tone.neutralBg,contentColor=tone.neutral,shape=MaterialTheme.shapes.small) {
        Text(campaignModeLabel(campaign.mode),
            Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium)
    }
}

@Composable internal fun ActionRow(title:String,description:String="",icon:ImageVector?=null,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel=title,onClick=onClick).heightIn(min=64.dp).padding(vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        if(icon!=null)Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(24.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium)
            if(description.isNotBlank())Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,modifier=Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CampaignSelector(campaigns:List<Campaign>,selected:Campaign?,onSelect:(String)->Unit,onCreate:(()->Unit)?=null) {
    var choosing by remember { mutableStateOf(false) }
    CampOutlinedCard(onClick={choosing=true},modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if(selected==null)"Contexto de conversación" else "Campaña seleccionada",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.name ?: "Nueva campaña",style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ExpandMore,"Cambiar campaña")
        }
    }
    if(choosing)ModalBottomSheet(onDismissRequest={choosing=false}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp)) {
            SectionHeading("Elegir campaña","El agente y la actividad mostrarán la campaña que elijas.")
            if(onCreate!=null)CampTextButton(onClick={choosing=false;onCreate()},modifier=Modifier.heightIn(min=48.dp)){Text("Nueva campaña por chat")}
            androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(top=12.dp).heightIn(max=440.dp)) {
                items(campaigns.size,key={campaigns[it].id}) { index->
                    val item=campaigns[index]
                    ListItem(headlineContent={Text(item.name)},supportingContent={Text(campaignModeLabel(item.mode))},
                        trailingContent={if(item.id==selected?.id)Icon(Icons.Outlined.Check,"Seleccionada")},
                        modifier=Modifier.clickable { onSelect(item.id);choosing=false }.heightIn(min=64.dp))
                }
            }
        }
    }
}
