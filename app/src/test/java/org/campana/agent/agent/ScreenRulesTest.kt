package org.campana.agent.agent

import org.junit.Assert.*
import org.junit.Test

class ScreenRulesTest {
    @Test fun consecutiveScreenshotRequestsWaitForTheNativeCaptureInterval() {
        assertEquals(0L,ScreenRules.captureWaitMillis(0,1000))
        assertEquals(454L,ScreenRules.captureWaitMillis(1000,1046))
        assertEquals(0L,ScreenRules.captureWaitMillis(1000,1500))
        assertEquals(0L,ScreenRules.captureWaitMillis(1000,2500))
        assertEquals(500L,ScreenRules.captureWaitMillis(1000,999))
    }
    @Test fun facebookFinalReviewRequiresAnImmediateVerifiedComposerTransition() {
        val composer=listOf(fb("Nueva publicación",97,221),fb("Página de ejemplo",311,368),fb("Texto autorizado",656,752,true),fb("",800,1800).copy(description="Foto"))
        assertTrue(ScreenRules.facebookAdvance("com.facebook.katana","Página de ejemplo",composer," Siguiente"))
        assertFalse(ScreenRules.facebookAdvance("com.facebook.katana","Otra",composer,"Siguiente"))
        assertFalse(ScreenRules.facebookAdvance("com.facebook.katana","Página de ejemplo",composer.dropLast(1),"Siguiente"))
        val review=listOf(fb("Configuración de la publicación",141,209),fb("Destinatarios de la publicación",277,332),fb("Opciones de programación",589,629),fb("COMPARTIR",125,226))
        val token=ScreenRules.FacebookReview("Página de ejemplo",9,1000)
        assertTrue(ScreenRules.facebookReviewMatches(token,"com.facebook.katana","Página de ejemplo",9,1100,review))
        assertFalse(ScreenRules.facebookReviewMatches(null,"com.facebook.katana","Página de ejemplo",9,1100,review))
        assertFalse(ScreenRules.facebookReviewMatches(token,"com.facebook.katana","Otra",9,1100,review))
        assertFalse(ScreenRules.facebookReviewMatches(token,"com.facebook.katana","Página de ejemplo",10,1100,review))
        assertFalse(ScreenRules.facebookReviewMatches(token,"com.facebook.katana","Página de ejemplo",9,121001,review))
        assertFalse(ScreenRules.facebookReviewMatches(token,"com.facebook.katana","Página de ejemplo",9,1100,review.drop(1)))
        assertFalse(ScreenRules.facebookReviewMatches(token,"com.facebook.katana","Página de ejemplo",9,1100,review+composer[2]))
    }
    @Test fun keepingAFacebookDraftDoesNotAuthorizeFollowingOrDiscarding() {
        val labels=listOf("¿Quieres terminar la publicación más tarde?","Descartar publicación","Seguir editando")
        assertTrue(ScreenRules.keepEditingDialog("com.facebook.katana","POST","","Seguir editando",labels))
        assertFalse(ScreenRules.keepEditingDialog("com.facebook.katana","POST","","Seguir",labels))
        assertFalse(ScreenRules.keepEditingDialog("com.facebook.katana","POST","","Descartar publicación",labels))
        assertFalse(ScreenRules.keepEditingDialog("com.facebook.katana","POST","","Seguir editando",emptyList()))
        assertFalse(ScreenRules.keepEditingDialog("com.facebook.orca","MESSAGE","","Seguir editando",labels))
    }
    @Test fun whatsappPlaceholderNeedsMicrophoneAndNoSendControl() {
        val microphone=listOf("com.whatsapp:id/voice_note_btn" to "")
        assertTrue(ScreenRules.emptyWhatsAppHint("com.whatsapp","com.whatsapp:id/entry","Mensaje",microphone))
        assertFalse(ScreenRules.emptyWhatsAppHint("com.whatsapp","com.whatsapp:id/entry","Mensaje",microphone+("com.whatsapp:id/send" to "")))
        assertFalse(ScreenRules.emptyWhatsAppHint("com.whatsapp","com.whatsapp:id/entry","Mensaje",emptyList()))
        assertFalse(ScreenRules.emptyWhatsAppHint("com.whatsapp","com.whatsapp:id/entry","Un borrador real",microphone))
        assertFalse(ScreenRules.emptyWhatsAppHint("com.whatsapp","com.whatsapp:id/profile_name","Mensaje",microphone))
    }
    private fun fb(text:String,top:Int,bottom:Int,editable:Boolean=false)=ActorEvidence(text,"",editable,20,top,1000,bottom)
    @Test fun onlyAnEmptyVerifiedFacebookComposerCanBeResetForAnAttachment() {
        val screen=listOf(fb("Nueva publicación",97,221),fb("Página de ejemplo",311,368),fb("¿Qué estás pensando?",656,752,true).copy(showingHint=true),fb("Siguiente",97,198).copy(enabled=false))
        assertTrue(ScreenRules.emptyFacebookComposer("com.facebook.katana","POST","Página de ejemplo",screen))
        assertFalse(ScreenRules.emptyFacebookComposer("com.facebook.katana","POST","Otra página",screen))
        assertFalse(ScreenRules.emptyFacebookComposer("com.facebook.katana","POST","Página de ejemplo",screen.map{if(it.editable)it.copy(text="Un borrador real",showingHint=false) else it}))
        assertFalse(ScreenRules.emptyFacebookComposer("com.facebook.katana","POST","Página de ejemplo",screen+fb("Foto adjunta",800,1400)))
        assertFalse(ScreenRules.emptyFacebookComposer("com.facebook.katana","POST","Página de ejemplo",screen.map{if(it.text=="Siguiente")it.copy(enabled=true) else it}))
    }
    @Test fun facebookAuthorMustBeBetweenNewPostTitleAndOnlyComposer() {
        val screen=listOf(fb("Nueva publicación",97,221),fb("Página de ejemplo",311,368),fb("¿Qué estás pensando?",656,752,true))
        assertTrue(ScreenRules.destinationMatches(emptyList(),"com.facebook.katana","Página de ejemplo",screen))
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.katana","Otra página",screen))
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.katana","Página de ejemplo",screen.drop(1)))
    }
    @Test fun facebookFeedMentionsAndAmbiguousAuthorsCannotAuthorizeAPost() {
        val screen=listOf(fb("Nueva publicación",97,221),fb("Página de ejemplo",311,368),fb("Texto",656,752,true))
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.katana","Página de ejemplo",screen+fb("Página de ejemplo",400,450)))
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.katana","Página de ejemplo",screen.filterNot{it.editable}))
        assertFalse(ScreenRules.destinationMatches(emptyList(),"com.facebook.katana","Página de ejemplo",listOf(screen[0],screen[2],fb("Página de ejemplo",800,900))))
    }
    @Test fun facebookComposerNavigationDoesNotGrantOtherAppOrMessagePermissions() {
        assertTrue(ScreenRules.isComposerEntry("com.facebook.katana","¿Qué estás pensando?","","POST"))
        assertFalse(ScreenRules.isComposerEntry("com.whatsapp","¿Qué estás pensando?","","POST"))
        assertFalse(ScreenRules.isComposerEntry("com.facebook.katana","¿Qué estás pensando?","","MESSAGE"))
        assertFalse(ScreenRules.isComposerEntry("com.facebook.katana","Editar página","","POST"))
    }
    @Test fun unlabeledSubmissionRequiresObservedExactResourceAndPackage() {
        assertTrue(ScreenRules.knownSubmission("com.instagram.android","com.instagram.android:id/row_thread_composer_button_send"))
        assertFalse(ScreenRules.knownSubmission("com.instagram.android","com.other:id/row_thread_composer_button_send"))
        assertFalse(ScreenRules.knownSubmission("com.facebook.katana","com.instagram.android:id/row_thread_composer_button_send"))
        assertFalse(ScreenRules.knownSubmission("com.instagram.android","com.instagram.android:id/profile_message_button"))
    }
    @Test fun selfSearchCanOmitSuffixWithoutChangingDestination() {
        assertTrue(ScreenRules.searchMatches("com.whatsapp","Ana","Ana (Tú)"))
        assertFalse(ScreenRules.searchMatches("com.whatsapp","Juan","Ana (Tú)"))
        assertFalse(ScreenRules.searchMatches("com.instagram.android","Ana","Ana (Tú)"))
        assertFalse(ScreenRules.destinationMatches(listOf("com.whatsapp:id/conversation_contact_name" to "Ana"),"com.whatsapp","Ana (Tú)"))
    }
    @Test fun instagramNewChatRequiresSingleSelectedRecipient() {
        val selected=listOf("com.instagram.android:id/direct_new_chat_to_field" to "Para:","com.instagram.android:id/recipients_container" to "Ana")
        assertTrue(ScreenRules.destinationMatches(selected,"com.instagram.android","Ana"))
        assertFalse(ScreenRules.destinationMatches(selected+("com.instagram.android:id/recipients_container" to "Juan"),"com.instagram.android","Ana"))
        assertFalse(ScreenRules.destinationMatches(selected.drop(1),"com.instagram.android","Ana"))
    }
    @Test fun titleFromAnotherScreenCannotProveDestination() {
        assertFalse(ScreenRules.destinationMatches(listOf("com.whatsapp:id/profile_name" to "Ana"),"com.whatsapp","Ana"))
        assertTrue(ScreenRules.destinationMatches(listOf("com.whatsapp:id/conversation_contact_name" to "Ana"),"com.whatsapp","Ana"))
    }
    @Test fun selectorRequiresOneExplicitRecipient() {
        val selected=listOf("com.whatsapp:id/recipients" to "Ana","" to "1 seleccionado")
        assertTrue(ScreenRules.destinationMatches(selected,"com.whatsapp","Ana"))
        assertFalse(ScreenRules.destinationMatches(selected.map { if(it.second=="1 seleccionado")it.first to "2 seleccionados" else it },"com.whatsapp","Ana"))
        assertFalse(ScreenRules.destinationMatches(listOf("com.whatsapp:id/recipients" to "Ana, Juan","" to "2 seleccionados"),"com.whatsapp","Ana"))
    }
    @Test fun searchAndNavigationDoNotGrantSubmission() {
        assertFalse(ScreenRules.isNavigation("Guardar perfil","com.whatsapp:id/save","Ana"))
        assertTrue(ScreenRules.isNavigation("Buscar","com.whatsapp:id/menuitem_search","Ana"))
        assertTrue(ScreenRules.isSubmission("Enviar","com.whatsapp:id/send"))
        assertFalse(ScreenRules.isSubmission("Enviar mensaje","com.instagram.android:id/profile_message_button"))
    }
    @Test fun profileFieldsAreNeverMessageComposers() {
        assertFalse(ScreenRules.writeAllowed("com.whatsapp","com.whatsapp:id/profile_name","Nombre","MESSAGE",true))
        assertTrue(ScreenRules.writeAllowed("com.whatsapp","com.whatsapp:id/entry","Mensaje","MESSAGE",true))
        assertFalse(ScreenRules.writeAllowed("com.whatsapp","com.whatsapp:id/entry","Mensaje","MESSAGE",false))
    }
}
