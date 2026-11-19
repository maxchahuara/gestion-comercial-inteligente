package org.campana.agent.agent

import org.campana.agent.data.Campaign
import org.campana.agent.data.Channel

data class PolicyDecision(val allowed: Boolean, val reason: String)

object Policy {
    fun isQuiet(hour: Int, start: Int, end: Int): Boolean =
        if (start == end) false else if (start < end) hour in start until end else hour >= start || hour < end

    fun evaluate(campaign: Campaign, channel: String, kind: String, recipient: String,
                 paused: Boolean, hour: Int, submissionsToday: Int, incomingVerified:Boolean=false): PolicyDecision {
        val continuous=campaign.isPublicInbox() && incomingVerified && kind=="REPLY"
        val reason = when {
            paused -> "El agente está pausado."
            campaign.mode !in setOf("AUTO","REACTIVE") -> "La campaña está en modo borradores."
            campaign.mode=="REACTIVE" && (kind!="REPLY" || !incomingVerified || !campaign.autoReply) -> "Este modo solo responde a mensajes directos recibidos y verificados; no inicia conversaciones ni publica."
            incomingVerified && !campaign.autoReply -> "Las respuestas entrantes están desactivadas."
            runCatching { Channel.valueOf(channel) }.getOrNull() !in campaign.channelList() -> "Canal no autorizado."
            !continuous && isQuiet(hour, campaign.quietStart, campaign.quietEnd) -> "Fuera del horario autorizado."
            !continuous && submissionsToday >= campaign.dailyLimit -> "Se alcanzó el límite diario."
            kind !in setOf("REPLY", "MESSAGE", "POST", "ATTACHMENT") -> "Tipo de acción no autorizado."
            kind == "POST" && !campaign.allowPublishing -> "Las publicaciones automáticas están desactivadas."
            kind == "POST" && campaign.accountIdentity.isBlank() -> "Indica la identidad visible de la cuenta."
            kind != "POST" && !(if(incomingVerified)campaign.allowsIncomingRecipient(recipient) else campaign.allowsRecipient(recipient)) -> "Destinatario fuera de la lista autorizada."
            else -> null
        }
        return PolicyDecision(reason == null, reason ?: "Autorizado")
    }
}
