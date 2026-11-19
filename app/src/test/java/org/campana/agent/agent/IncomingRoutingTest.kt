package org.campana.agent.agent

import org.campana.agent.data.*
import org.campana.agent.phone.*
import org.junit.Assert.*
import org.junit.Test

class IncomingRoutingTest {
    @Test fun aDedicatedWhatsAppCampaignCannotKeepTheLegacyTestContactRestriction() {
        val legacy=Campaign(mode="REACTIVE",autoReply=true,recipients="Contacto anterior",channels="WHATSAPP",updatedAt=100)
        val newcomer=IncomingMessage("new-conversation","new-fingerprint",Channel.WHATSAPP,"Remitente nuevo sin registrar","Hola",1000,direct=true)
        assertEquals(legacy.id,IncomingRouting.campaign(listOf(legacy),newcomer,false,500,1100)?.id)
        assertTrue(Policy.evaluate(legacy,"WHATSAPP","REPLY",newcomer.title,false,12,0,incomingVerified=true).allowed)
        assertFalse(Policy.evaluate(legacy,"WHATSAPP","MESSAGE",newcomer.title,false,12,0,incomingVerified=true).allowed)
        assertFalse(Policy.evaluate(legacy,"WHATSAPP","REPLY",newcomer.title,true,12,0,incomingVerified=true).allowed)
    }
    private val c=Campaign(mode="AUTO",autoReply=true,channels="WHATSAPP,INSTAGRAM",recipients="Amigo QA",updatedAt=100)
    private val message=IncomingMessage("key","fingerprint",Channel.WHATSAPP,"Amigo QA","Hola",1000,direct=true)
    private fun route(list:List<Campaign> = listOf(c),m:IncomingMessage=message,paused:Boolean=false,since:Long=500,now:Long=1100)=IncomingRouting.campaign(list,m,paused,since,now)
    @Test fun onlyAFreshDirectMessageCanSelectACampaign() {
        assertEquals(c.id,route()?.id)
        assertNull(route(m=message.copy(direct=false)))
        assertNull(route(paused=true));assertNull(route(since=1001));assertNull(route(now=1000+IncomingNotificationRules.MAX_PENDING_AGE_MILLIS+1))
        assertNull(route(m=message.copy(timestamp=2000)));assertNull(route(m=message.copy(title="Otra persona")))
    }
    @Test fun anAmbiguousCampaignOrInactiveModeCannotReply() {
        assertNull(route(listOf(c,c.copy(id=newId()))))
        assertNull(route(listOf(c.copy(mode="DRAFT"))))
        assertNull(route(listOf(c.copy(autoReply=false))))
        assertNull(route(listOf(c.copy(updatedAt=1001))))
        assertNull(route(listOf(c.copy(channels="INSTAGRAM"))))
    }
    @Test fun wildcardAllowsOnlyAnExplicitReactiveCampaign() {
        assertEquals(c.id,route(listOf(c.copy(mode="REACTIVE",recipients="*")),message.copy(title="Primer interesado"))?.id)
        assertNull(route(listOf(c.copy(mode="AUTO",recipients="*")),message.copy(title="Primer interesado")))
    }
    @Test fun activeUnreadMessagesCanRecoverAfterAListenerReconnect() {
        assertEquals(c.id,route(now=1000+60*60_000)?.id)
        assertNull(route(now=1000+60*60_000,since=1001))
    }
    @Test fun exactNativePhoneCanIdentifyAContactWithADifferentDisplayName() {
        val allowed=c.copy(recipients="+12025550101")
        val incoming=message.copy(title="Nombre completo QA",senderPhone="+1 202 555 0101")
        assertEquals(allowed.id,route(listOf(allowed),incoming)?.id)
        assertEquals("+12025550101",incoming.recipientFor(allowed))
        assertNull(route(listOf(allowed),incoming.copy(senderPhone="+12025550102")))
        assertEquals("",IncomingNotificationRules.phone("https://example.invalid/12025550101"))
        assertEquals("+12025550101",IncomingNotificationRules.phone("tel:+12025550101"))
    }
    @Test fun onlyWhatsAppCanOmitTheCategoryInANativeMessage() {
        assertTrue(IncomingNotificationRules.category(Channel.WHATSAPP,null))
        assertTrue(IncomingNotificationRules.category(Channel.INSTAGRAM,"msg"))
        assertFalse(IncomingNotificationRules.category(Channel.INSTAGRAM,null))
        assertTrue(IncomingNotificationRules.category(Channel.MESSENGER,null))
        assertFalse(IncomingNotificationRules.category(Channel.WHATSAPP,"social"))
    }
    @Test fun identicalNamesAndMessagesInDifferentConversationsAreIndependent() {
        val first=IncomingNotificationRules.fingerprint(Channel.WHATSAPP,"native-thread-a","Persona QA","Hola",1000)
        assertEquals(first,IncomingNotificationRules.fingerprint(Channel.WHATSAPP,"native-thread-a","Persona QA","Hola",1000))
        assertNotEquals(first,IncomingNotificationRules.fingerprint(Channel.WHATSAPP,"native-thread-b","Persona QA","Hola",1000))
        assertNotEquals(first,IncomingNotificationRules.fingerprint(Channel.INSTAGRAM,"native-thread-a","Persona QA","Hola",1000))
    }
}
