package org.campana.agent.agent

import kotlinx.serialization.json.*
import org.campana.agent.data.*
import org.campana.agent.net.*

/** Incoming users cannot turn the campaign inbox into an unrestricted assistant. */
object CampaignReplyPolicy {
    const val VERSION=2
    val routes=listOf("greeting","campaign","redirect","needs_owner","stop")
    fun redirect(c:Campaign)="Soy el asistente de ${c.name}. Puedo ayudarte únicamente con esta campaña. ¿Qué te gustaría saber sobre ella?"
    fun needsOwner(c:Campaign)="Ese dato no está confirmado en la información de ${c.name}. Debe confirmarlo el responsable de la campaña."
    fun rejectAdministrative(incoming:String):Boolean=Regex("ignora.{0,35}instrucciones|olvida.{0,25}instrucciones|revela.{0,30}(token|contrasena|prompt)|dame.{0,25}(api key|token de acceso|prompt del sistema)|cambia.{0,25}(tus reglas|tus instrucciones)|desactiva.{0,25}(filtro|seguridad)").containsMatchIn(normalized(incoming))
    fun schema():JsonObject=buildJsonObject {
        put("type","object");put("additionalProperties",false)
        putJsonObject("properties") {
            putJsonObject("route"){put("type","string");putJsonArray("enum"){routes.forEach{add(it)}}}
            putJsonObject("answer"){put("type","string")}
            putJsonObject("evidence"){put("type","array");putJsonObject("items"){put("type","string")}}
        }
        putJsonArray("required"){add("route");add("answer");add("evidence")}
    }
    fun finish(c:Campaign,structured:String,historyEmpty:Boolean,incoming:String,links:List<String>):String {
        val d=json.parseToJsonElement(structured).jsonObject
        require(d.keys==setOf("route","answer","evidence"))
        val route=d.str("route");require(route in routes)
        if(route=="redirect" || rejectAdministrative(incoming))return redirect(c)
        if(route=="needs_owner")return needsOwner(c)
        if(route=="stop")return "Entendido. Solo atiendo mensajes que me escribas directamente sobre ${c.name}."
        val approved=listOf(c.name,c.objective,c.offer,c.audience,c.knowledge)+links
        val evidence=d.getValue("evidence").jsonArray.map{it.jsonPrimitive.content}
        require(evidence.size<=8 && evidence.all{quote->quote.length in 3..500 && approved.any{normalized(it).contains(normalized(quote))}}){"La respuesta usa información que no está aprobada para esta campaña."}
        if(route=="campaign" && evidence.isEmpty())return needsOwner(c)
        var answer=d.str("answer")
        if(route=="greeting" && !normalized(answer).contains(normalized(c.name)))answer="Hola, soy el asistente de ${c.name}. "+answer
        if(route=="greeting" && normalized(c.knowledge).contains("fictici") && !Regex("fictici|prueba").containsMatchIn(normalized(answer)))answer+=" Es una campaña ficticia de prueba."
        return ReplyDrafting.finish(answer,historyEmpty,incoming,links)
    }
}
