package org.campana.agent.agent

import org.campana.agent.data.*
import org.campana.agent.phone.ScreenNode
import org.junit.Assert.*
import org.junit.Test

class ContactIdentityRulesTest {
    private val c=Campaign(mode="AUTO",autoReply=true,channels="WHATSAPP,INSTAGRAM",recipients="Amigo\n+12025550101")
    private fun n(id:Int,text:String,resource:String,editable:Boolean=false)=ScreenNode(id,text,"","com.whatsapp:id/"+resource,editable,true,false,0,id*100,1000,id*100+80)
    @Test fun aLongerDisplayNameOnlyStartsReadVerificationAndNeverAuthorizesAReply() {
        assertTrue(ContactIdentityRules.candidate(c,"Amigo Apellido"))
        assertFalse(c.allowsIncomingRecipient("Amigo Apellido"))
        assertFalse(ContactIdentityRules.candidate(c,"Amigote Apellido"))
        assertFalse(ContactIdentityRules.candidate(c.copy(recipients="Amigo"),"Amigo Apellido"))
        assertFalse(ContactIdentityRules.candidate(c.copy(mode="DRAFT"),"Amigo Apellido"))
        assertFalse(ContactIdentityRules.candidate(c.copy(autoReply=false),"Amigo Apellido"))
        assertFalse(ContactIdentityRules.candidate(c.copy(mode="REACTIVE",channels="WHATSAPP"),"Amigo Apellido"))
    }
    @Test fun onlyNativeProfileNameAndNumberCanEstablishAnIdentity() {
        val nodes=listOf(n(1,"Amigo Apellido","contact_info_name"),n(2,"+1 202 555 0101","contact_info_phone_number"))
        assertEquals("+12025550101",ContactIdentityRules.profilePhone(nodes,"Amigo Apellido",c.recipients.lines()))
        assertNull(ContactIdentityRules.profilePhone(nodes,"Otra persona",c.recipients.lines()))
        assertNull(ContactIdentityRules.profilePhone(nodes,"Amigo Apellido",listOf("+12025550102")))
        assertNull(ContactIdentityRules.profilePhone(nodes+n(3,"Mensaje","entry",true),"Amigo Apellido",c.recipients.lines()))
        assertNull(ContactIdentityRules.profilePhone(nodes+n(4,"Amigo Apellido","conversation_contact_name"),"Amigo Apellido",c.recipients.lines()))
        assertNull(ContactIdentityRules.profilePhone(nodes.map{it.copy(resource="com.whatsapp:id/message_text")},"Amigo Apellido",c.recipients.lines()))
    }
    @Test fun profileNavigationCannotSendOrModifyAnything() {
        assertTrue(ContactIdentityRules.navigation("Amigo Apellido"))
        assertTrue(ContactIdentityRules.navigation("Información del contacto"))
        for(label in listOf("Enviar","Llamar","Videollamada","Editar","Bloquear","Reportar","Silenciar","Compartir","Agregar contacto","Mensaje"))assertFalse(label,ContactIdentityRules.navigation(label))
    }
    @Test fun theNativeContactInfoCardsExposeTheirTitleAndPhoneAsSubtitle() {
        val nodes=listOf(n(1,"","contact_details_card"),n(2,"","top_info_card"),n(3,"Amigo Apellido","contact_title"),n(4,"\u200e+1 202 555 0101","contact_subtitle"))
        assertEquals("+12025550101",ContactIdentityRules.profilePhone(nodes,"Amigo Apellido",c.recipients.lines()))
        assertNull(ContactIdentityRules.profilePhone(nodes.filterNot{it.resource.endsWith("contact_details_card")},"Amigo Apellido",c.recipients.lines()))
        assertNull(ContactIdentityRules.profilePhone(nodes.map{if(it.resource.endsWith("contact_subtitle"))it.copy(text="+1 202 555 0102")else it},"Amigo Apellido",c.recipients.lines()))
    }
}
