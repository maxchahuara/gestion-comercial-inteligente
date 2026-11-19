package org.campana.agent.agent
import org.junit.Assert.*
import org.junit.Test

class MessengerDestinationTest {
    private val back=ActorEvidence("","Volver",false,10,100,140,240)
    private val header=ActorEvidence("","Visitante QA, Detalles de la conversación · Visitante QA",false,150,100,700,240)
    private val composer=ActorEvidence("Mensaje","Escribe un mensaje",true,450,2000,850,2200)
    @Test fun nativeDetailsAndBackRowVerifyTheConversationWithoutViewIds() {
        assertTrue(ScreenRules.destinationMatches(emptyList(),"com.facebook.orca","Visitante QA",listOf(back,header,composer)))
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.orca","Otra persona",listOf(back,header,composer)))
    }
    @Test fun aNameOrForgedMessageInTheBodyCannotReplaceTheNativeHeader() {
        val body=header.copy(top=600,bottom=800)
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.orca","Visitante QA",listOf(back,body,composer)))
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.orca","Visitante QA",listOf(header,composer)))
    }
    @Test fun theLikeButtonProvesAnEmptyMessengerPlaceholderButNeverASubmission() {
        assertTrue(ScreenRules.emptyMessengerHint("com.facebook.orca","Mensaje","Escribe un mensaje",listOf("Enviar un Me gusta")))
        assertFalse(ScreenRules.emptyMessengerHint("com.facebook.orca","Mensaje","Escribe un mensaje",listOf("Enviar")))
        assertFalse(ScreenRules.emptyMessengerHint("com.facebook.orca","Borrador humano","Escribe un mensaje",listOf("Enviar un Me gusta")))
        assertFalse(ScreenRules.isSubmission("Enviar un Me gusta",""))
    }
    @Test fun aTextSendCanReuseALikeResourceWithoutAllowingALike() {
        assertFalse(ScreenRules.unsafeControl("click",false,"","Enviar","com.facebook.orca:id/send_like_button"))
        assertFalse(ScreenRules.unsafeControl("click",false,"","Enviar","com.facebook.orca:id/(name removed)"))
        assertTrue(ScreenRules.unsafeControl("click",false,"","Enviar un Me gusta","com.facebook.orca:id/send_like_button"))
        assertTrue(ScreenRules.unsafeControl("click",false,"Eliminar","","com.facebook.orca:id/send_button"))
        assertFalse(ScreenRules.unsafeControl("click",true,"Me gusta esta campaña","Escribe un mensaje","composer"))
    }
}
