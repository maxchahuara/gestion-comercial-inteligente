package org.campana.agent.agent

import org.junit.Assert.*
import org.junit.Test

class PostLinkRulesTest {
    private val appName = "Gestión Comercial Inteligente | {ℂℎ}"
    @Test fun identicalFullScreenContainersMustNotReplaceTheObservedScrollableNode() {
        val root=org.campana.agent.phone.ScreenNode(1,"","","",false,false,false,0,0,1080,2205)
        val scroll=root.copy(id=8,scrollable=true)
        assertEquals(8,PostLinkRules.liveNode(listOf(root,scroll),scroll)?.id)
        assertNull(PostLinkRules.liveNode(listOf(scroll.copy(scrollable=false)),scroll))
    }
    private val url="https://www.facebook.com/share/p/Example123/"
    @Test fun aSharedPostUrlMustBeSingleHttpsAndFromFacebook() {
        assertEquals(url,PostLinkRules.facebookUrl("Mira el post: $url"))
        assertNull(PostLinkRules.facebookUrl("$url $url"))
        assertNull(PostLinkRules.facebookUrl("http://www.facebook.com/share/p/Example123/"))
        assertNull(PostLinkRules.facebookUrl("https://facebook.com.evil.invalid/share/p/Example123/"))
        assertNull(PostLinkRules.facebookUrl("https://user:secret@facebook.com/share/p/Example123/"))
        assertNull(PostLinkRules.facebookUrl("https://facebook.com/"))
    }
    private fun node(text:String,top:Int,bottom:Int,description:String="")=ActorEvidence(text,description,false,0,top,1000,bottom)
    @Test fun sharingRequiresAuthorCaptionPhotoAndTheSamePostFooter() {
        val share=node("",1600,1700,"Botón \"Compartir\". Toca dos veces para compartir la publicación.")
        val nodes=listOf(node("Página QA",100,150),node("Descripción aprobada",180,250),node("",300,1500,"Foto"),share)
        assertTrue(PostLinkRules.verifiedPost(nodes,"Página QA","Descripción aprobada",share))
        assertFalse(PostLinkRules.verifiedPost(nodes,"Otra página","Descripción aprobada",share))
        assertFalse(PostLinkRules.verifiedPost(nodes,"Página QA","Otro post",share))
        assertFalse(PostLinkRules.verifiedPost(nodes.filterNot{it.description=="Foto"},"Página QA","Descripción aprobada",share))
        assertFalse(PostLinkRules.verifiedPost(nodes+node("Página QA",1550,1580),"Página QA","Descripción aprobada",share))
    }
    @Test fun navigationIsChosenFromTheScreenWithoutGrantingEffects() {
        assertTrue(PostLinkRules.navigation("com.facebook.katana","Ver perfil de la página",false,appName))
        assertTrue(PostLinkRules.navigation("com.facebook.katana","Fotos",false,appName))
        for(label in listOf("Enviar","Compartir ahora","Editar publicación","Eliminar","Me gusta","Promocionar","Copiar enlace","Guardar publicación"))
            assertFalse(label,PostLinkRules.navigation("com.facebook.katana",label,true,appName))
        assertFalse(PostLinkRules.navigation("com.whatsapp",appName,true,appName))
    }
    @Test fun onlyTheOwnShareTargetCanBeChosenInASystemSheet() {
        assertTrue(PostLinkRules.navigation("android",appName,true,appName))
        assertFalse(PostLinkRules.navigation("android",appName,false,appName))
        assertFalse(PostLinkRules.navigation("android","WhatsApp",true,appName))
        assertFalse(PostLinkRules.navigation("android","Un contacto",true,appName))
    }
    @Test fun aSystemShareSheetCanExpandAppsWithoutAuthorizingRecipients() {
        for(pkg in listOf("android","com.android.intentresolver","com.samsung.android.app.sharechooser")) {
            for(label in listOf("Más","Más opciones","More","More apps")) {
                assertTrue(label,PostLinkRules.navigation(pkg,label,true,appName))
                assertFalse(label,PostLinkRules.navigation(pkg,label,false,appName))
            }
            for(label in listOf("WhatsApp","Contacto de prueba","Copiar vínculo","Enviar","Compartir ahora","Más contactos"))
                assertFalse(label,PostLinkRules.navigation(pkg,label,true,appName))
        }
    }
    @Test fun anExpiredChooserRemainsReadableWithoutPermissionToShare() {
        assertTrue(PostLinkRules.readableSurface("com.android.intentresolver","org.campana.agent",false))
        assertFalse(PostLinkRules.navigation("com.android.intentresolver",appName,false,appName))
        assertFalse(PostLinkRules.navigation("com.android.intentresolver","Más",false,appName))
        assertFalse(PostLinkRules.readableSurface("org.campana.agent","org.campana.agent",false))
        assertTrue(PostLinkRules.readableSurface("org.campana.agent","org.campana.agent",true))
        assertFalse(PostLinkRules.readableSurface("com.whatsapp","org.campana.agent",true))
        assertTrue(PostLinkRules.ownShareTarget("$appName · $appName", appName))
        assertFalse(PostLinkRules.ownShareTarget("$appName ajena", appName))
        assertFalse(PostLinkRules.ownShareTarget("Campaña", appName))
        val voiceLabel = "Transcribir audio · {ℂℎ}"
        assertTrue(PostLinkRules.ownShareTarget("$appName · $voiceLabel", voiceLabel, appName))
        assertTrue(PostLinkRules.ownShareTarget("$voiceLabel $voiceLabel", voiceLabel, appName))
        assertFalse(PostLinkRules.ownShareTarget(appName, voiceLabel, appName))
    }
    @Test fun aNativePhotoViewerCanVerifyTheSameCaptionAndAuthorWithoutTheFeedLayout() {
        val share=node("",2050,2120,"Botón \"Compartir\". Toca dos veces para compartir la publicación.")
        val screen=listOf(node("1 of 5",90,170),node("Página QA",1650,1700),node("Descripción aprobada",1750,1930),share)
        assertTrue(PostLinkRules.verifiedPost(screen,"Página QA","Descripción aprobada",share))
        assertFalse(PostLinkRules.verifiedPost(screen,"Otra página","Descripción aprobada",share))
        assertFalse(PostLinkRules.verifiedPost(screen.drop(1),"Página QA","Descripción aprobada",share))
        assertFalse(PostLinkRules.verifiedPost(screen+node("Editable",300,400).copy(editable=true),"Página QA","Descripción aprobada",share))
    }
}
