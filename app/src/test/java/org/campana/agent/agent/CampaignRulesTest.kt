package org.campana.agent.agent

import org.campana.agent.data.Campaign
import org.junit.Assert.*
import org.junit.Test

class CampaignRulesTest {
    @Test fun aDedicatedWhatsAppNumberAcceptsAnyVerifiedIncomingWithoutInitiating() {
        val original=Campaign(mode="AUTO",channels="WHATSAPP,INSTAGRAM",recipients="Persona de prueba",autoReply=false,allowPublishing=true,accountIdentity="Página",dailyLimit=37,quietStart=22,quietEnd=7)
        val rules=CampaignRules.from(original).dedicatedWhatsApp()
        rules.validate()
        val dedicated=rules.applyTo(original)
        assertEquals("WHATSAPP",dedicated.channels)
        assertEquals(37,dedicated.dailyLimit);assertEquals(22,dedicated.quietStart);assertEquals(7,dedicated.quietEnd)
        assertTrue(Policy.evaluate(dedicated,"WHATSAPP","REPLY","Contacto desconocido",false,12,0,true).allowed)
        assertFalse(Policy.evaluate(dedicated,"WHATSAPP","REPLY","Contacto desconocido",false,12,0,false).allowed)
        assertFalse(Policy.evaluate(dedicated,"WHATSAPP","MESSAGE","Contacto desconocido",false,12,0,true).allowed)
        assertFalse(Policy.evaluate(dedicated,"WHATSAPP","REPLY","Contacto desconocido",true,12,0,true).allowed)
        assertFalse(Policy.evaluate(dedicated,"INSTAGRAM","REPLY","Contacto desconocido",false,12,0,true).allowed)
    }
    @Test fun reactiveAuthorizationRequiresAttentionAndForbidsPublishing() {
        val reactive=rules.copy(mode="REACTIVE",recipients="Contacto",autoReply=true)
        reactive.validate()
        rejected(reactive.copy(recipients=""));rejected(reactive.copy(autoReply=false))
        rejected(reactive.copy(allowPublishing=true,accountIdentity="Página"))
        rejected(reactive.copy(mode="AUTO",recipients="*"))
        reactive.copy(recipients="*").validate()
    }
    @Test fun reactiveSummaryExplainsTheDifferenceBetweenRespondingAndInitiating() {
        val summary=rules.copy(mode="REACTIVE",autoReply=true,recipients="*").summary()
        assertTrue(summary.contains("Cualquier persona que escriba directamente"))
        assertTrue(summary.contains("Iniciar conversaciones: No permitido"))
        assertTrue(summary.contains("Publicaciones: Desactivadas"))
    }
    private val rules=CampaignRules.from(Campaign())
    @Test fun publicMessengerRequiresThePageAndExplainsContinuousReplies() {
        val page=rules.copy(mode="REACTIVE",channels="WHATSAPP,MESSENGER",recipients="",autoReply=true,accountIdentity="Página QA").canonical()
        page.validate();assertEquals("*",page.recipients)
        assertTrue(page.summary().contains("Fanpage de Messenger: Página QA"))
        assertTrue(page.summary().contains("atención continua"))
        rejected(page.copy(accountIdentity=""))
    }
    private fun rejected(value:CampaignRules) {
        try { value.validate();fail("Debió rechazar las reglas") } catch(_:IllegalArgumentException) {}
    }
    @Test fun automaticRequiresAnExplicitDestinationAndPublishingIdentity() {
        rules.validate()
        rejected(rules.copy(mode="AUTO"))
        rejected(rules.copy(mode="AUTO",recipients="Contacto",allowPublishing=true))
        rules.copy(mode="AUTO",recipients="Contacto").validate()
        rules.copy(mode="AUTO",allowPublishing=true,accountIdentity="Mi cuenta").validate()
    }
    @Test fun unknownChannelsAndOutOfRangeLimitsCannotBeApproved() {
        rejected(rules.copy(channels=""));rejected(rules.copy(channels="WHATSAPP,WHATSAPP"))
        rejected(rules.copy(channels="OTRA"));rejected(rules.copy(mode="UNRESTRICTED"))
        rejected(rules.copy(dailyLimit=0));rejected(rules.copy(dailyLimit=501))
        rejected(rules.copy(quietStart=24));rejected(rules.copy(quietEnd=-1))
    }
    @Test fun applyingRulesPreservesBusinessContextAndCampaignIdentity() {
        val c=Campaign(name="Mi negocio",objective="Objetivo",offer="Oferta",knowledge="Datos aprobados")
        val changed=rules.copy(mode="AUTO",recipients="Contacto",dailyLimit=3).applyTo(c)
        assertEquals(c.id,changed.id);assertEquals(c.name,changed.name)
        assertEquals(c.objective,changed.objective);assertEquals(c.offer,changed.offer)
        assertEquals(c.knowledge,changed.knowledge);assertEquals(3,changed.dailyLimit)
    }
    @Test fun authorizationSummaryIncludesFullDestinationsAndSilenceHours() {
        val longName="Contacto con un nombre largo que debe verse completo y sin puntos suspensivos"
        val summary=rules.copy(recipients=longName+"\nSegundo destino",quietStart=22,quietEnd=7).summary()
        assertTrue(summary.contains(longName));assertTrue(summary.contains("Segundo destino"))
        assertTrue(summary.contains("22:00 a 07:00"));assertTrue(summary.contains("Publicaciones: Desactivadas"))
    }
}
