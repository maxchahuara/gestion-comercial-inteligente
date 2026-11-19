package org.campana.agent.agent

import org.campana.agent.data.Campaign
import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun reactiveModeCannotInitiateEvenIfANewTaskCallsItAReply() {
        val c=campaign.copy(mode="REACTIVE",autoReply=true,channels="WHATSAPP,MESSENGER")
        for(kind in listOf("MESSAGE","POST","ATTACHMENT","REPLY"))assertFalse(Policy.evaluate(c,"WHATSAPP",kind,"Ana Pérez",false,12,0).allowed)
        assertTrue(Policy.evaluate(c,"WHATSAPP","REPLY","Ana Pérez",false,12,0,incomingVerified=true).allowed)
        assertTrue(Policy.evaluate(c,"WHATSAPP","REPLY","Otra persona",false,12,0,incomingVerified=true).allowed)
    }
    @Test fun continuousPublicRepliesDoNotStopAtTheOldPublicationQuotaOrQuietHours() {
        val c=campaign.copy(mode="REACTIVE",autoReply=true,recipients="*",channels="WHATSAPP,MESSENGER")
        assertTrue(Policy.evaluate(c,"MESSENGER","REPLY","Persona nueva",false,23,999,true).allowed)
        assertFalse(Policy.evaluate(c,"MESSENGER","REPLY","Persona nueva",true,23,999,true).allowed)
        assertFalse(Policy.evaluate(c,"MESSENGER","MESSAGE","Persona nueva",false,23,999,true).allowed)
    }
    @Test fun anySenderIsOnlyForVerifiedIncomingInReactiveMode() {
        val c=campaign.copy(mode="REACTIVE",autoReply=true,recipients="*")
        assertTrue(Policy.evaluate(c,"WHATSAPP","REPLY","Nuevo interesado",false,12,0,true).allowed)
        assertFalse(Policy.evaluate(c,"WHATSAPP","MESSAGE","Nuevo interesado",false,12,0,true).allowed)
        assertFalse(Policy.evaluate(c.copy(mode="AUTO"),"WHATSAPP","REPLY","Nuevo interesado",false,12,0,true).allowed)
        assertFalse(c.allowsRecipient("Nuevo interesado"))
    }
    @Test fun incomingRepliesRespectPauseLimitsChannelsAndQuietHours() {
        val c=campaign.copy(mode="AUTO",autoReply=true)
        assertFalse(Policy.evaluate(c,"WHATSAPP","REPLY","Ana Pérez",true,12,0,true).allowed)
        assertFalse(Policy.evaluate(c,"WHATSAPP","REPLY","Ana Pérez",false,12,3,true).allowed)
        assertFalse(Policy.evaluate(c,"WHATSAPP","REPLY","Ana Pérez",false,23,0,true).allowed)
        assertFalse(Policy.evaluate(c,"INSTAGRAM","REPLY","Ana Pérez",false,12,0,true).allowed)
        assertFalse(Policy.evaluate(c.copy(autoReply=false),"WHATSAPP","REPLY","Ana Pérez",false,12,0,true).allowed)
    }
    @Test fun phoneFormattingDoesNotAllowPartialNumbersOrNames() {
        assertTrue(campaign.allowsRecipient("+1 (202) 555-0101"))
        assertFalse(campaign.allowsRecipient("2025550101"))
        assertFalse(campaign.allowsRecipient("Contacto +12025550101"))
        assertFalse(campaign.allowsRecipient("+120255501010"))
    }
    private val campaign = Campaign(name="Prueba", mode="AUTO", recipients="Ana Pérez\n+12025550101", dailyLimit=3)
    private fun check(c: Campaign = campaign, channel: String = "WHATSAPP", kind: String = "REPLY",
                      recipient: String = "Ana Pérez", paused: Boolean = false, hour: Int = 12, count: Int = 0) =
        Policy.evaluate(c, channel, kind, recipient, paused, hour, count).allowed
    @Test fun onlyAuthorizedRecipient() { assertTrue(check()); assertFalse(check(recipient="Ana")); assertFalse(check(recipient="Otra persona")) }
    @Test fun normalizationDoesNotPermitPartialMatch() { assertTrue(check(recipient="  ANA PEREZ ")); assertFalse(check(recipient="Ana Pérez 2")) }
    @Test fun pauseAndDraftBlockEffects() { assertFalse(check(paused=true)); assertFalse(check(c=campaign.copy(mode="DRAFT"))) }
    @Test fun dailyLimitAndChannelAreEnforced() { assertFalse(check(count=3)); assertFalse(check(channel="INSTAGRAM")); assertFalse(check(channel="UNKNOWN")) }
    @Test fun quietHoursAcrossMidnight() { assertFalse(check(hour=23)); assertFalse(check(hour=7)); assertTrue(check(hour=8)) }
    @Test fun daytimeQuietHoursAndDisabledWindow() { assertTrue(Policy.isQuiet(12,9,17)); assertFalse(Policy.isQuiet(18,9,17)); assertFalse(Policy.isQuiet(12,0,0)) }
    @Test fun postsNeedSeparateAuthorizationAndIdentity() {
        assertFalse(check(kind="POST"))
        assertFalse(check(c=campaign.copy(allowPublishing=true),kind="POST"))
        assertTrue(check(c=campaign.copy(allowPublishing=true,accountIdentity="@miempresa"),kind="POST"))
    }
    @Test fun arbitraryActionCannotBypassPolicy() { assertFalse(check(kind="BUY")); assertFalse(check(kind="DELETE")) }
}
