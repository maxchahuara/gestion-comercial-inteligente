package org.campana.agent.agent

import org.campana.agent.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReplyDraftingTest {
    private val link="https://www.facebook.com/share/p/Example123/"
    @Test fun theFirstResponseIncludesTheVerifiedLinkWithoutInventingIt() {
        val result=ReplyDrafting.finish("¡Hola! Soy el asistente de la campaña ficticia.",true,"Hola",listOf(link))
        assertTrue(result.contains(link))
        assertEquals(1,Regex(Regex.escape(link)).findAll(ReplyDrafting.finish(result,true,"Hola",listOf(link))).count())
    }
    @Test fun followUpAnswersDoNotRepeatTheLinkUnlessNeeded() {
        assertEquals("Marca el 8.",ReplyDrafting.finish("Marca el 8.",false,"Qué número",listOf(link)))
        assertTrue(ReplyDrafting.finish("Aquí tienes.",false,"Pásame el enlace",listOf(link)).contains(link))
    }
    @Test fun aMadeUpFacebookLinkIsRejectedBeforeAnySend() {
        try {ReplyDrafting.finish("Mira https://www.facebook.com/share/p/Fake/",true,"Hola",listOf(link));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun rehearsalAndRealRepliesUseTheSameRequestWithoutSendingTools() {
        val c=Campaign(name="Campaña QA",recipients="No exponer",accountIdentity="Cuenta privada",knowledge="Prueba ficticia. Número: 8.")
        val body=ReplyDrafting.request(c,emptyList(),"Hola","gpt-6.1-terra",listOf(link))
        val instructions=body.getValue("instructions").jsonPrimitive.content
        assertFalse(instructions.contains("No exponer"));assertFalse(instructions.contains("Cuenta privada"))
        assertTrue(instructions.contains(link));assertTrue(instructions.contains("nombre de la campaña"))
        assertNull(body["tools"]);assertFalse(body.getValue("store").jsonPrimitive.boolean)
    }
}
