package org.campana.agent.agent

import org.campana.agent.phone.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MessengerVoiceRulesTest {
    private val zone=ZoneId.of("America/Lima")
    private val now=ZonedDateTime.of(2026,11,13,17,30,0,0,zone).toInstant().toEpochMilli()
    private val marker=", toca dos veces para ver la fecha y hora de envío o recepción, toca dos veces y mantén presionado para reaccionar al mensaje"
    private fun n(id:Int,label:String,l:Int,t:Int,r:Int,b:Int,editable:Boolean=false)=ScreenNode(id,label,label,"",editable,false,false,l,t,r,b,nativeDescription=label)
    private val root=listOf(n(0,"",0,0,1080,2340),n(1,"Volver",11,109,146,244),n(2,"Visitante QA, Detalles de la conversación",157,109,704,244),
        n(3,"Página QA, Respuesta anterior"+marker,305,1036,1057,1612),n(4,"5:17 P.M.",471,1652,608,1698),n(5,"Abrir el perfil de Visitante QA",34,1827,113,1906),
        n(6,"Visitante envió un clip de voz. Haz clic para reproducirlo o pausarlo.",147,1726,866,1906).copy(resource="com.facebook.orca:id/(name removed)",longClickable=true),
        n(7,"Reproducir mensaje de voz de Visitante QA, 5:17\u202fp.m.",181,1760,294,1873).copy(resource="com.facebook.orca:id/(name removed)",clickable=true),
        n(8,"Reproductor de audio",327,1760,697,1872).copy(resource="com.facebook.orca:id/(name removed)"),n(9,"0:06",745,1794,818,1837),
        n(10,"Herramientas empresariales",336,2037,448,2205),n(11,"Mensaje",448,2062,854,2180,true))
    private fun pending(nodes:List<ScreenNode> = root)=MessengerInboxRules.pending(ScreenState("native","com.facebook.orca",7,now,nodes),now,zone=zone)
    @Test fun aNativeVoiceRowAfterAnOwnTextIsDetectedAsTheLatestIncomingMessage() {
        val e=pending()!!
        assertEquals("Mensaje de voz",e.message);assertEquals("Visitante QA",e.sender)
        assertEquals(LocalTime.of(17,17),Instant.ofEpochMilli(e.receivedAt).atZone(zone).toLocalTime())
        assertTrue(e.previousOwn.contains("Respuesta anterior"))
        assertTrue(e.nativeMessage.contains("0:06"))
    }
    @Test fun aQuotedVoiceLabelWithoutNativePlayerCannotCreateAnIncomingAudio() {
        assertNull(pending(root.filter{it.id!=7}))
        assertNull(pending(root.filter{it.id!=8}))
        assertNull(pending(root.map{if(it.id==6)it.copy(longClickable=false)else it}))
    }
    @Test fun aPlayerForAnotherPersonDoesNotMatchTheNativeVoiceRow() {
        assertNull(pending(root.map{if(it.id==7)it.copy(nativeDescription="Reproducir mensaje de voz de Otra persona, 5:17 p.m.")else it}))
    }
    @Test fun anOutgoingVoiceNeverStartsAnAutomaticReply() {
        assertNull(pending(root.map{when(it.id){6->it.copy(left=305,right=1057);7->it.copy(left=339,right=452);8->it.copy(left=485,right=855);else->it}}))
    }
}
