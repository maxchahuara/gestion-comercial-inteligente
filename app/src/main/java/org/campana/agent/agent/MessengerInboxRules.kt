package org.campana.agent.agent

import kotlinx.serialization.Serializable
import org.campana.agent.phone.*
import org.campana.agent.data.*
import java.time.*

@Serializable data class InboxMessageEvidence(val sender:String,val message:String,val nativeMessage:String,
    val receivedAt:Long,val windowId:Int,val history:String,val previousOwn:String="")

/** Parses the native speaker/date semantics of a Page conversation, never model-supplied text. */
object MessengerInboxRules {
    private val markers=listOf(", toca dos veces para ver la fecha y hora de envío o recepción",", double tap to see the date and time")
    private fun raw(n:ScreenNode)=n.nativeDescription.ifBlank{n.text}
    fun header(nodes:List<ScreenNode>):String? {
        val back=nodes.filter{!it.editable && normalized(raw(it)) in setOf("volver","back") && it.bottom>it.top}.minByOrNull{it.top} ?: return null
        return nodes.mapNotNull { n->
            if(n.editable || n.left<back.right || n.top>=back.bottom || n.bottom<=back.top)return@mapNotNull null
            val label=raw(n)
            val suffix=listOf(", Detalles de la conversación",", Conversation details").firstOrNull{normalized(label).endsWith(normalized(it))} ?: return@mapNotNull null
            label.dropLast(suffix.length).trim().takeIf{it.isNotBlank() && it.length<=200}
        }.distinct().singleOrNull()
    }
    private data class Bubble(val node:ScreenNode,val speaker:String,val body:String,val raw:String,val voiceClock:Pair<ScreenNode,LocalTime>?=null)
    private fun bubbles(nodes:List<ScreenNode>,headerBottom:Int,composerTop:Int):List<Bubble> = (nodes.mapNotNull { n->
        if(n.editable || n.top<headerBottom || n.bottom>composerTop || n.bottom<=n.top)return@mapNotNull null
        val value=raw(n);val marker=markers.firstOrNull{value.lastIndexOf(it)>=0} ?: return@mapNotNull null
        val lead=value.substring(0,value.lastIndexOf(marker));val comma=lead.indexOf(", ")
        if(comma<=0)return@mapNotNull null
        val speaker=lead.substring(0,comma);val body=lead.substring(comma+2).trim()
        if(body.isBlank() || body.length>6000)return@mapNotNull null
        Bubble(n,speaker,body,value)
    }+nodes.mapNotNull{row->
        if(row.editable || !row.longClickable || !row.resource.startsWith("com.facebook.orca:id/") || row.top<headerBottom || row.bottom>composerTop)return@mapNotNull null
        val m=Regex("^(.+) envió un clip de voz\\. Haz clic para reproducirlo o pausarlo\\.$").matchEntire(raw(row)) ?: return@mapNotNull null
        val inside=nodes.filter{it.left>=row.left && it.right<=row.right && it.top>=row.top && it.bottom<=row.bottom}
        val play=inside.filter{it.clickable && it.resource.startsWith("com.facebook.orca:id/")}.mapNotNull{n->
            val v=Regex("^Reproducir mensaje de voz de (.+), ([0-9].*)$").matchEntire(raw(n)) ?: return@mapNotNull null
            clock(v.groupValues[2])?.let{Triple(n,v.groupValues[1],it)}
        }.singleOrNull() ?: return@mapNotNull null
        val first=normalized(m.groupValues[1]);val full=normalized(play.second)
        if(full!=first && !full.startsWith(first+" "))return@mapNotNull null
        if(inside.none{normalized(raw(it))=="reproductor de audio" && it.resource.startsWith("com.facebook.orca:id/")})return@mapNotNull null
        val duration=inside.map{it.text}.firstOrNull{Regex("^[0-9]{1,2}:[0-5][0-9]$").matches(it)}.orEmpty()
        Bubble(row,play.second,"Mensaje de voz",raw(row)+"\n"+raw(play.first)+"\n"+duration,play.first to play.third)
    }).distinctBy{listOf(it.raw,it.node.top,it.node.bottom)}.sortedBy{it.node.bottom}
    fun pending(state:ScreenState,now:Long,requireBusinessTools:Boolean=true,zone:ZoneId=ZoneId.systemDefault()):InboxMessageEvidence? {
        if(state.packageName!=Channel.MESSENGER.packageName)return null
        if(requireBusinessTools && state.nodes.none{normalized(raw(it)) in setOf("herramientas empresariales","business tools")})return null
        val sender=header(state.nodes) ?: return null
        val composer=state.nodes.singleOrNull{it.editable && it.enabled} ?: return null
        val head=state.nodes.filter{normalized(raw(it))==normalized(sender+", Detalles de la conversación") || normalized(raw(it))==normalized(sender+", Conversation details")}.minByOrNull{it.top} ?: return null
        val messages=bubbles(state.nodes,head.bottom,composer.top);val last=messages.lastOrNull() ?: return null
        if(normalized(last.speaker) in setOf("tu","you","me"))return null
        val width=state.nodes.maxOfOrNull{it.right} ?: return null
        val left=last.node.left<width*.4 && last.node.right<width*.94
        val senderPrefix=normalized(sender)==normalized(last.speaker) || normalized(sender).startsWith(normalized(last.speaker)+" ")
        val avatar=state.nodes.any{n->listOf("Abrir el perfil de $sender","Open $sender's profile").any{sameContact(raw(n),it)} && n.left<last.node.left && n.top<last.node.bottom && n.bottom>last.node.top}
        if(!left || (!senderPrefix && !avatar))return null
        val time=last.voiceClock ?: state.nodes.filter{!it.editable && it.top<last.node.top && it.top>=head.bottom}.mapNotNull{n->clock(raw(n))?.let{n to it}}.maxByOrNull{it.first.top} ?: return null
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date=state.nodes.filter{it.top>=head.bottom && it.top<time.first.top}.mapNotNull{n->date(raw(n),today)?.let{n.top to it}}.maxByOrNull{it.first}?.second ?: today
        val received=date.atTime(time.second).atZone(zone).toInstant().toEpochMilli()
        if(received>now+60_000)return null
        val previousOwn=messages.dropLast(1).lastOrNull{normalized(it.speaker) in setOf("tu","you","me") || (it.node.right>=width*.94 && it.node.left>=width*.25 && !sameContact(it.speaker,sender))}?.raw.orEmpty()
        return InboxMessageEvidence(sender,last.body,last.raw,received,state.windowId,messages.takeLast(6).joinToString("\n"){it.speaker+": "+it.body},previousOwn)
    }
    fun clock(label:String):LocalTime? {
        val value=normalized(label).replace('\u202f',' ').replace('\u00a0',' ').replace(".","")
        val m=Regex("^(\\d{1,2}):(\\d{2})\\s*([ap]m)?$").matchEntire(value) ?: return null
        var hour=m.groupValues[1].toInt();val minute=m.groupValues[2].toInt();val suffix=m.groupValues[3]
        if(suffix.isNotBlank()){if(hour !in 1..12)return null;hour=hour%12+if(suffix=="pm")12 else 0}
        return runCatching{LocalTime.of(hour,minute)}.getOrNull()
    }
    private fun date(label:String,today:LocalDate):LocalDate? {
        val value=normalized(label).replace('\u202f',' ').replace('\u00a0',' ').replace(".","")
        if(value in setOf("hoy","today"))return today
        if(value in setOf("ayer","yesterday"))return today.minusDays(1)
        val months=listOf(listOf("ene","enero","jan","january"),listOf("feb","febrero","february"),listOf("mar","marzo","march"),listOf("abr","abril","apr","april"),listOf("may","mayo"),listOf("jun","junio","june"),listOf("jul","julio","july"),listOf("ago","agosto","aug","august"),listOf("sep","septiembre","september"),listOf("oct","octubre","october"),listOf("nov","noviembre","november"),listOf("dic","diciembre","dec","december"))
        val m=Regex("^(\\d{1,2}) (?:de )?([a-z]+) (?:de )?(\\d{4})$").matchEntire(value) ?: return null
        val month=months.indexOfFirst{m.groupValues[2] in it}+1
        return runCatching{LocalDate.of(m.groupValues[3].toInt(),month,m.groupValues[1].toInt())}.getOrNull()
    }
}
