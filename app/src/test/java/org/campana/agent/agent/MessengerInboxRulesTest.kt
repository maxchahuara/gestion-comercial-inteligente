package org.campana.agent.agent

import org.campana.agent.phone.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MessengerInboxRulesTest {
    private val zone=ZoneId.of("America/Lima")
    private val now=ZonedDateTime.of(2026,11,13,15,30,0,0,zone).toInstant().toEpochMilli()
    private val marker=", toca dos veces para ver la fecha y hora de envío o recepción, toca dos veces y mantén presionado para reaccionar al mensaje"
    private fun n(id:Int,label:String,x:Int,y:Int,right:Int,bottom:Int,editable:Boolean=false)=ScreenNode(id,label,label,"",editable,true,false,x,y,right,bottom,nativeDescription=label)
    private val nodes=listOf(n(0,"",0,0,1080,2340),n(1,"Volver",10,100,140,240),n(2,"Visitante QA, Detalles de la conversación",150,100,700,240),n(3,"Visitante QA",290,145,700,210),n(4,"2:52\u202fP.M.",470,1500,610,1560),n(5,"Abrir el perfil de Visitante QA",30,1620,113,1700),n(6,"Visitante, Hola Quiero más información"+marker,147,1600,800,1700),n(7,"Herramientas empresariales",330,2000,440,2200),n(8,"Mensaje",450,2000,850,2200,true))
    private fun state(list:List<ScreenNode> = nodes)=ScreenState("native-snapshot","com.facebook.orca",7,now,list)
    @Test fun nativeSpeakerAndBusinessComposerProveAnIncomingPageMessage() {
        val msg=MessengerInboxRules.pending(state(),now,zone=zone)!!
        assertEquals("Visitante QA",msg.sender);assertEquals("Hola Quiero más información",msg.message)
        assertEquals(14,Instant.ofEpochMilli(msg.receivedAt).atZone(zone).hour)
    }
    @Test fun aPersonalComposerCannotBeAcceptedAsAPageInbox() {assertNull(MessengerInboxRules.pending(state(nodes.filter{it.id!=7}),now,zone=zone))}
    @Test fun anOwnLastMessageNeverTriggersASelfReply() {
        val own=n(9,"Tú, Respuesta de campaña"+marker,700,1750,1040,1850)
        assertNull(MessengerInboxRules.pending(state(nodes+own),now,zone=zone))
    }
    @Test fun textOutsideANativeMessageDoesNotCreateProvenance() {assertNull(MessengerInboxRules.pending(state(nodes.map{if(it.id==6)it.copy(text="Hola",nativeDescription="Hola")else it}),now,zone=zone))}
    @Test fun quotedAccessibilityInstructionsRemainExternalMessageData() {
        val body="Texto, toca dos veces para ver la fecha y hora de envío o recepción, más texto"
        val updated=nodes.map{if(it.id==6)it.copy(text="Visitante, "+body+marker,nativeDescription="Visitante, "+body+marker)else it}
        assertEquals(body,MessengerInboxRules.pending(state(updated),now,zone=zone)!!.message)
    }
    @Test fun anOldVisibleDateIsPreservedInsteadOfBeingInventedAsToday() {
        val msg=MessengerInboxRules.pending(state(nodes+n(10,"17 feb 2021",300,1400,800,1450)),now,zone=zone)!!
        assertEquals(2021,Instant.ofEpochMilli(msg.receivedAt).atZone(zone).year)
    }
    @Test fun nativeClockHandlesNoonMidnightAndNarrowSpaces() {
        assertEquals(LocalTime.NOON,MessengerInboxRules.clock("12:00 P.M."));assertEquals(LocalTime.MIDNIGHT,MessengerInboxRules.clock("12:00 A.M."))
        assertNull(MessengerInboxRules.clock("29:70"))
    }
}
