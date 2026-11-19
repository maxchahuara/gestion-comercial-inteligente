package org.campana.agent.ui

import org.campana.agent.data.*
import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    @Test fun formattingPreservesBusinessFactsAndCode() {
        val source="## Oferta\n**Precio:** 25 soles\n- Incluye entrega\n```\nconst value = \"**literal**\";\n```"
        val blocks=messageBlocks(source)
        assertEquals("heading",blocks.first().kind)
        assertTrue(plainMessage(source).contains("Precio: 25 soles"))
        assertTrue(plainMessage(source).contains("const value = \"**literal**\";"))
        assertFalse(plainMessage(source).contains("```"))
    }
    @Test fun tablesBecomeLabeledMobileRowsWithoutLosingCells() {
        val blocks=messageBlocks("| Día | Contenido |\n| --- | --- |\n| Lunes | Oferta |\n| Martes | Horarios |")
        assertEquals(2,blocks.size)
        assertTrue(plainMessage(blocks[0].text).contains("Día: Lunes"))
        assertTrue(plainMessage(blocks[1].text).contains("Contenido: Horarios"))
    }
    @Test fun externalMarkupRemainsInertText() {
        val source="<script>acción</script> [sitio](https://example.invalid) [otro](javascript:acción)"
        val text=inlineMessage(source)
        assertTrue(text.text.contains("<script>acción</script>"))
        assertTrue(text.text.contains("https://example.invalid"))
        assertTrue(text.getStringAnnotations(0,text.length).isEmpty())
    }
    @Test fun observedReceiptWithErrorIsNotPresentedAsSuccess() {
        val receipt=Receipt(campaignId="qa",action="screen_action",status="OBSERVED",detail="""{"result":"{\"error\":\"La pantalla cambió\"}"}""")
        val summary=receiptSummary(receipt,null)
        assertTrue(summary.error)
        assertEquals("No se completó la acción",summary.title)
    }
    @Test fun receiptRecordedBeforeSubmissionCannotProveDelivery() {
        val receipt=Receipt(campaignId="qa",action="external_submit",status="SUBMITTING",detail="Intento")
        val task=AgentTask(campaignId="qa",channel="WHATSAPP",instruction="QA",status="UNCERTAIN")
        val summary=receiptSummary(receipt,task)
        assertTrue(summary.error)
        assertTrue(summary.description.contains("Resultado incierto"))
        assertFalse(summary.description.contains("entregado",ignoreCase=true))
    }
    @Test fun importedUnexpectedReceiptPayloadDoesNotCrashOrClaimSuccess() {
        val receipt=Receipt(campaignId="qa",action="unknown",status="OBSERVED",detail="""{"result":{"unexpected":true}}""")
        assertEquals("Acción registrada",receiptSummary(receipt,null).title)
    }
}
