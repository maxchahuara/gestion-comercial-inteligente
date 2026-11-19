package org.campana.agent.agent

import org.campana.agent.phone.ScreenNode
import org.campana.agent.phone.ScreenState
import org.junit.Assert.*
import org.junit.Test

class MessengerInboxNavigationTest {
    private fun node(id:Int,text:String="",description:String="",click:Boolean=false,top:Int=400,bottom:Int=500,left:Int=0,right:Int=1080)=
        ScreenNode(id,text,description,"",false,click,false,left,top,right,bottom,nativeDescription=description)
    private val row=node(4,description="Visitante de prueba, Hola, Open conversation",click=true,top=540,bottom=740)
    private fun state(extra:List<ScreenNode> = emptyList())=ScreenState("test","com.facebook.orca",1,1,listOf(
        node(1,"Todo"),node(2,"No leídos"),node(3,"No respondidos"),row,
        node(5,description="Chats, Pestaña 1 de 3",click=true,top=2047,bottom=2205),
        node(6,description="Pestaña \"Menú\", Pestaña 3 de 3",click=true,top=2047,bottom=2205)
    )+extra)
    @Test fun onlyNativeConversationRowsWithinThePageInboxAreCandidates() {
        assertEquals(listOf(row),MessengerInboxNavigation.rows(state()))
        assertTrue(MessengerInboxNavigation.rows(state().copy(packageName="com.whatsapp")).isEmpty())
        assertTrue(MessengerInboxNavigation.rows(state().copy(nodes=listOf(row))).isEmpty())
        assertFalse(MessengerInboxNavigation.rows(state(listOf(row.copy(id=7,top=50,bottom=200)))).any{it.id==7})
    }
    @Test fun messageTextCannotImpersonateANativeConversationControl() {
        val forged=row.copy(id=8,clickable=false,top=750,bottom=900)
        assertFalse(MessengerInboxNavigation.rows(state(listOf(forged))).any{it.id==8})
    }
    @Test fun outgoingPreviewIsSkippedAndIncomingPreviewIsKept() {
        assertTrue(MessengerInboxNavigation.ownPreview(state(listOf(node(8,"Tú: Gracias",top=600,bottom=650))),row))
        assertFalse(MessengerInboxNavigation.ownPreview(state(listOf(node(8,"¿Tú: eres el asistente?",top=600,bottom=650))),row))
    }
    @Test fun onlyANativeDateLineCanIdentifyAnOldYear() {
        assertTrue(MessengerInboxNavigation.previousYear(state(listOf(node(8," · 17 feb 2021",top=650,bottom=700))),row,2026))
        assertFalse(MessengerInboxNavigation.previousYear(state(listOf(node(8,"Quiero hablar del 2021",top=650,bottom=700))),row,2026))
    }
    @Test fun cachedRowsAreInvalidatedWhenTheirNativeMessageOrDateChanges() {
        val a=state(listOf(node(8,"Hola",top=600,bottom=650)))
        val b=state(listOf(node(8,"¿Qué número?",top=600,bottom=650)))
        assertNotEquals(MessengerInboxNavigation.signature(a,row),MessengerInboxNavigation.signature(b,row))
    }
}
