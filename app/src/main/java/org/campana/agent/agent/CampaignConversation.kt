package org.campana.agent.agent

import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.campana.agent.AppGraph
import org.campana.agent.data.*
import org.campana.agent.net.json

@Serializable
data class CampaignRules(
    val channels:String, val mode:String, val recipients:String,
    val accountIdentity:String, val dailyLimit:Int, val quietStart:Int, val quietEnd:Int,
    val autoReply:Boolean, val allowPublishing:Boolean
) {
    fun dedicatedWhatsApp()=copy(channels="WHATSAPP",mode="REACTIVE",recipients="*",autoReply=true,allowPublishing=false)
    fun canonical()=if(mode=="REACTIVE" && autoReply)copy(recipients="*") else this
    fun validate() {
        require(mode in setOf("DRAFT","AUTO","REACTIVE")) { "Elige borradores, solo respuestas o automático." }
        val selected=channels.split(",")
        require(selected.isNotEmpty() && selected.distinct().size==selected.size && selected.all { name->Channel.entries.any { it.name==name } }) { "Selecciona canales válidos." }
        require(dailyLimit in 1..500 && quietStart in 0..23 && quietEnd in 0..23) { "Revisa el límite diario y las horas." }
        require(recipients.length<=10000 && recipients.lines().filter { it.isNotBlank() }.all { it.length<=200 } && accountIdentity.length<=200) { "Revisa los nombres de destino." }
        if(recipients.lines().any{it.trim()=="*"})require(mode=="REACTIVE" && recipients.trim()=="*") { "Cualquier remitente solo se permite en el modo de respuestas entrantes." }
        if(mode=="REACTIVE")require(autoReply && !allowPublishing && recipients.isNotBlank()) { "Solo respuestas exige activar atención, indicar remitentes y desactivar publicaciones." }
        if(mode!="DRAFT" && autoReply && "MESSENGER" in selected)require(accountIdentity.isNotBlank()){ "Indica la fanpage de Messenger; la cuenta personal no autoriza atender la campaña." }
        if(mode=="AUTO") {
            require(recipients.isNotBlank() || (allowPublishing && accountIdentity.isNotBlank())) { "Indica a quién puede escribir o en qué cuenta puede publicar." }
            require(!allowPublishing || accountIdentity.isNotBlank()) { "Indica la identidad visible de la cuenta para publicar." }
        }
    }
    fun applyTo(c:Campaign):Campaign = c.copy(channels=channels,mode=mode,recipients=canonical().recipients,
        accountIdentity=accountIdentity,dailyLimit=dailyLimit,quietStart=quietStart,quietEnd=quietEnd,
        autoReply=autoReply,allowPublishing=allowPublishing,updatedAt=System.currentTimeMillis())
    fun summary():String = listOf(
        "Modo: "+when(mode){"AUTO"->"Automático según estas reglas";"REACTIVE"->"Solo responder mensajes entrantes";else->"Solo borradores"},
        "Canales: "+channels.split(",").joinToString(" · ") { name->Channel.valueOf(name).title },
        (if(mode=="REACTIVE")"Puede responder a: " else "Puede escribir a: ")+if(canonical().recipients.trim()=="*")"Cualquier persona que escriba directamente" else recipients.ifBlank { "Nadie" },
        if(mode=="REACTIVE")"Respuestas: sin cupo diario; solo a mensajes entrantes" else "Límite: $dailyLimit acciones al día",
        if(mode=="REACTIVE")"Horario: atención continua hasta que recuperes el control" else "Horario: "+if(quietStart==quietEnd)"Sin horario de silencio" else "Silencio de %02d:00 a %02d:00, hora del teléfono".format(quietStart,quietEnd),
        if("MESSENGER" in channels.split(","))"Fanpage de Messenger: "+accountIdentity.ifBlank{"Pendiente de identificar"} else "",
        "Respuestas entrantes: "+if(autoReply)"Solo mensajes directos nuevos; no grupos, comentarios ni notificaciones generales" else "Desactivadas",
        "Iniciar conversaciones: "+if(mode=="REACTIVE")"No permitido" else if(mode=="AUTO")"Según destinatarios y reglas" else "No permitido",
        "Publicaciones: "+if(allowPublishing)"Permitidas en "+accountIdentity.ifBlank { "Cuenta pendiente de identificar" } else "Desactivadas"
    ).filter{it.isNotBlank()}.joinToString("\n")
    companion object {
        fun from(c:Campaign)=CampaignRules(c.channels,c.mode,c.recipients,c.accountIdentity,c.dailyLimit,c.quietStart,c.quietEnd,c.autoReply,c.allowPublishing).canonical()
    }
}

@Serializable data class RulesProposal(val before:CampaignRules,val proposed:CampaignRules)

/** Administrative conversation only. Proposals never grant permission until a native human action. */
class CampaignConversation(private val g:AppGraph) {
    val submitting=MutableStateFlow(false)
    /** Product correction requested by the owner: an already-active dedicated number has no test-contact filter. */
    internal suspend fun migrateDedicatedScope()=g.db.withTransaction {
        val c=g.dao.campaign(g.activeCampaign.value) ?: return@withTransaction
        if(!c.isDedicatedWhatsApp() || c.recipients.trim()=="*")return@withTransaction
        // No campaign content or listening cutoff changes: the actual pending greeting remains eligible.
        g.dao.save(c.copy(recipients="*"))
        g.dao.resolveRuleProposals(c.id,"Propuesta anterior retirada: WhatsApp dedicado atiende a cualquier remitente directo.")
        val detail="WhatsApp dedicado atiende automáticamente a cualquier persona que escriba directamente. Se retiró el filtro de contactos de prueba. Se conservan pausa, horarios, límites y contenido de campaña."
        g.dao.insert(Receipt(campaignId=c.id,action="dedicated_scope_upgrade",status="CONFIGURED",detail=detail))
        g.dao.insert(ChatMessage(campaignId=c.id,role="notice",text=detail))
        g.trace("dedicated_whatsapp_scope_migrated scope=ANY_DIRECT")
    }

    /** Called only by the owner's native button, never exposed as a model/incoming tool. */
    internal suspend fun useDedicatedWhatsApp(id:String) {
        g.ready.await()
        g.db.withTransaction {
            val c=checkNotNull(g.dao.campaign(id))
            check(g.dao.campaigns().none{it.id!=id && it.mode in setOf("REACTIVE","AUTO") && it.autoReply && Channel.WHATSAPP in it.channelList()}){"Otra campaña ya atiende WhatsApp. Desactiva su atención antes de dedicar este número a esta campaña."}
            val rules=CampaignRules.from(c).dedicatedWhatsApp().also{it.validate()}
            if(CampaignRules.from(c)==rules)return@withTransaction
            g.dao.save(rules.applyTo(c))
            val summary="WhatsApp dedicado a esta campaña. Responderé a cualquier persona que escriba directamente, de forma continua hasta que recuperes el control. No necesito una lista de contactos. El estado de pausa se conserva."
            g.dao.resolveRuleProposals(id,"Propuesta retirada al elegir WhatsApp dedicado desde el teléfono.")
            g.dao.insert(Receipt(campaignId=id,action="dedicated_whatsapp_confirmation",status="HUMAN_APPROVED",detail=rules.summary()))
            g.dao.insert(ChatMessage(campaignId=id,role="notice",text=summary))
        }
        g.trace("dedicated_whatsapp_applied scope=ANY_DIRECT")
        g.setIncomingWake(true)
        g.postLinks.ensurePreparation()
        g.status.value=if(g.paused.value)"WhatsApp dedicado configurado. El agente sigue pausado." else "WhatsApp dedicado: atento a cualquier persona que escriba directamente."
    }

    suspend fun ensureDraft(id:String):Campaign = g.db.withTransaction {
        g.dao.campaign(id) ?: Campaign(id=id,name="Campaña en preparación",objective="").also { g.dao.save(it) }
    }

    suspend fun send(id:String,text:String,newCampaign:Boolean) {
        require(text.isNotBlank() && text.length<=12000)
        if(!submitting.compareAndSet(false,true))return
        try {
            g.ready.await()
            if(newCampaign)ensureDraft(id) else checkNotNull(g.dao.campaign(id)) { "La campaña ya no está disponible." }
            if(newCampaign)g.selectCampaign(id)
            g.engine.chat(id,text)
        } finally { submitting.value=false;if(!g.paused.value)g.postLinks.ensurePreparation() }
    }

    suspend fun propose(id:String,requested:CampaignRules):String = g.db.withTransaction {
        val rules=requested.canonical()
        rules.validate()
        val c=checkNotNull(g.dao.campaign(id))
        val before=CampaignRules.from(c)
        g.dao.resolveRuleProposals(id,"Propuesta anterior retirada. Las reglas siguen vigentes hasta una nueva confirmación.")
        if(before==rules)return@withTransaction "Las reglas ya coinciden. Se retiraron las propuestas anteriores sin cambiar ninguna autorización."
        val message=ChatMessage(campaignId=id,role="rules_proposal",text=json.encodeToString(RulesProposal(before,rules)))
        g.dao.insert(message)
        "Propuesta guardada para revisión humana en el chat. Sigue vigente el modo ${c.mode}; no se ha autorizado ni programado ningún envío con estas reglas."
    }

    suspend fun resolve(messageId:String,campaignId:String,approve:Boolean):String = g.db.withTransaction {
        val message=checkNotNull(g.dao.message(messageId)) { "La propuesta ya no está disponible." }
        require(message.campaignId==campaignId && message.role=="rules_proposal") { "La propuesta ya fue resuelta o pertenece a otra campaña." }
        if(!approve) {
            g.dao.resolveRuleProposal(messageId,"Propuesta descartada. Las reglas no cambiaron.")
            return@withTransaction "Propuesta descartada."
        }
        val c=checkNotNull(g.dao.campaign(campaignId))
        val proposal=json.decodeFromString<RulesProposal>(message.text)
        proposal.proposed.validate()
        if(CampaignRules.from(c)!=proposal.before) {
            g.dao.resolveRuleProposal(messageId,"La configuración cambió después de esta propuesta. Pide al agente una propuesta actualizada; no se aplicaron cambios.")
            return@withTransaction "La propuesta quedó desactualizada. Pide una nueva al agente."
        }
        g.dao.save(proposal.proposed.applyTo(c))
        val summary="Reglas confirmadas por ti.\n"+proposal.proposed.summary()+"\nLos borradores existentes se conservan y el estado de pausa no cambia."
        check(g.dao.resolveRuleProposal(messageId,summary)==1)
        g.dao.insert(Receipt(campaignId=campaignId,action="rules_confirmation",status="HUMAN_APPROVED",detail=summary))
        "Reglas aplicadas. Los borradores existentes no se ejecutaron."
    }
}
