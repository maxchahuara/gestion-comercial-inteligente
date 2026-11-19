package org.campana.agent.agent

import org.campana.agent.data.*
import org.campana.agent.phone.IncomingMessage
import org.junit.Assert.*
import org.junit.Test

class MessengerAccountRulesTest {
    private val c=Campaign(name="Campaña QA",accountIdentity="Página QA")
    private val message=IncomingMessage("native-key","fp",Channel.MESSENGER,"Visitante QA","Hola",1,direct=true)
    @Test fun unknownOrPersonalAccountNeverInheritsTheCampaign() {
        assertFalse(message.matchesCampaignAccount(c))
        assertFalse(message.copy(nativeAccount="Perfil personal QA").matchesCampaignAccount(c))
        assertFalse(message.copy(title="Página QA").matchesCampaignAccount(c))
    }
    @Test fun onlyTheExplicitNativeRecipientAccountCanMatch() {
        assertTrue(message.copy(nativeAccount="PÁGINA QA").matchesCampaignAccount(c))
        assertFalse(message.copy(nativeAccount="Página QA extra").matchesCampaignAccount(c))
        assertFalse(message.copy(nativeAccount="Página QA").matchesCampaignAccount(c.copy(accountIdentity="")))
    }
    @Test fun dedicatedWhatsAppStillAcceptsAnyDirectSender() {
        assertTrue(message.copy(channel=Channel.WHATSAPP).matchesCampaignAccount(c))
    }
    @Test fun actualNativeMenuTabRetainsRoleAndBadgeWithoutBecomingAChatTarget() {
        assertTrue(MessengerAccountRules.menuTab("Pestaña \"Menú\", 2 actualizaciones nuevas, Pestaña 4 de 4"))
        assertTrue(MessengerAccountRules.menuTab("Menu tab, 2 new updates"))
        assertFalse(MessengerAccountRules.menuTab("Visitante QA: abre el menú"))
        assertFalse(MessengerAccountRules.menuTab("Pestaña \"Chats\", Pestaña 1 de 4"))
        assertTrue(MessengerAccountRules.chatTab("Chats, 1 mensaje no leído, Pestaña 1 de 3"))
        assertFalse(MessengerAccountRules.chatTab("Visitante QA: abre Chats"))
    }
    @Test fun pageNameInAChatOrAccountListCannotCertifyTheCurrentProfile() {
        fun n(id:Int,text:String,top:Int,bottom:Int)=org.campana.agent.phone.ScreenNode(id,text,"","",false,false,false,0,top,500,bottom)
        val header=n(1,"Menú",20,100);val page=n(2,"Página QA",120,160);val switch=n(3,"Cambiar de perfil",170,200)
        assertTrue(MessengerAccountRules.activePageMenu(listOf(header,page,switch),"Página QA"))
        assertFalse(MessengerAccountRules.activePageMenu(listOf(page,switch),"Página QA"))
        assertFalse(MessengerAccountRules.activePageMenu(listOf(header,page.copy(top=300,bottom=350),switch),"Página QA"))
        assertFalse(MessengerAccountRules.activePageMenu(listOf(header,page,switch),"Otra página"))
        assertFalse(MessengerAccountRules.activePageMenu(listOf(header.copy(text="Cambiar de perfil"),page,switch),"Página QA"))
        assertTrue(MessengerAccountRules.activePageMenu(listOf(header.copy(text="",description="Menú"),page.copy(text="",description="Página QA"),switch.copy(text="",description="Cambiar de perfil")),"Página QA"))
    }
    @Test fun activatingPageAlertsRequiresItsExactDisabledBannerAndButtonRow() {
        fun n(id:Int,label:String,top:Int,bottom:Int)=org.campana.agent.phone.ScreenNode(id,label,"","",false,true,false,0,top,500,bottom)
        val banner=n(1,"Todas las notificaciones de esta página están desactivadas.",100,200)
        val activate=n(2,"Activar",120,180)
        assertTrue(MessengerAccountRules.activatePageAlerts(listOf(banner,activate),activate))
        assertFalse(MessengerAccountRules.activatePageAlerts(listOf(activate),activate))
        assertFalse(MessengerAccountRules.activatePageAlerts(listOf(banner,activate),activate.copy(top=600,bottom=680)))
        assertFalse(MessengerAccountRules.activatePageAlerts(listOf(banner,activate),activate.copy(text="Configurar")))
        val inbox=listOf(n(3,"Todo",300,350),n(4,"No leídos",300,350),n(5,"No respondidos",300,350))
        assertTrue(MessengerAccountRules.pageInbox(inbox));assertFalse(MessengerAccountRules.pageInbox(inbox.drop(1)))
    }
    @Test fun pageRoleSuffixInTheNativeChoiceDoesNotPermitAnotherPage() {
        assertTrue(MessengerAccountRules.selectorLabel("Cambiar de perfil"))
        assertTrue(MessengerAccountRules.selectorLabel("Switch profile, heading"))
        assertTrue(MessengerAccountRules.pageChoice("Página QA · Página","Página QA"))
        assertTrue(MessengerAccountRules.pageChoice("Página QA, Page","Página QA"))
        assertTrue(MessengerAccountRules.pageChoice("Cambiar a Página QA · Página QA","Página QA"))
        assertFalse(MessengerAccountRules.pageChoice("Cambiar a Otra página · Página QA","Página QA"))
        assertFalse(MessengerAccountRules.pageChoice("Página QA extra · Página","Página QA"))
        assertFalse(MessengerAccountRules.pageChoice("Página QA · Tú: hola","Página QA"))
    }
}
