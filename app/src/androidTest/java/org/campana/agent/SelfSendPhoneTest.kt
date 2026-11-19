package org.campana.agent

import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.campana.agent.data.*
import org.campana.agent.phone.PhoneAccessibilityService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.pdf.PdfDocument
import android.graphics.Paint

@RunWith(AndroidJUnit4::class)
class SelfSendPhoneTest {
    @Test fun agentSendsOneImageToVerifiedSelfChat()=runBlocking { attachment("image") }
    @Test fun agentSendsOnePdfToVerifiedSelfChat()=runBlocking { attachment("pdf") }
    private suspend fun attachment(kind:String) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit attachment test arguments.",args.getString("attachments")=="true")
        val recipient=args.getString("recipient") ?: error("Falta el chat propio.")
        require(recipient.endsWith("(Tú)") || recipient.endsWith("(You)"))
        val context=ApplicationProvider.getApplicationContext<Context>()
        val g=(context.applicationContext as CampaignApp).graph;g.ready.await()
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component=context.packageName+"/org.campana.agent.phone.PhoneAccessibilityService"
        val before=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        require(component in before.split(":") && before.matches(Regex("[A-Za-z0-9_.$:/]*")))
        fun shell(command:String) { automation.executeShellCommand(command).use { p->java.io.FileInputStream(p.fileDescriptor).use { it.readBytes() } } }
        val remaining=before.split(":").filter { it!=component }.joinToString(":")
        shell(if(remaining.isEmpty())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services "+remaining)
        delay(1200)
        shell("settings put secure enabled_accessibility_services "+before)
        shell("settings put secure accessibility_enabled 1")
        val deadline=SystemClock.elapsedRealtime()+8000
        while(PhoneAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline)delay(100)
        val service=checkNotNull(PhoneAccessibilityService.instance)
        val initial=withContext(Dispatchers.Main) { service.observe() }
        require(initial.packageName=="com.whatsapp" && initial.nodes.any { it.resource=="com.whatsapp:id/conversation_contact_name" && it.text==recipient })
        val previousUi=g.prefs.getBoolean("ui_enabled",false);val previousPause=g.paused.value
        g.prefs.edit().putBoolean("ui_enabled",true).commit();g.pause(false)
        val c=Campaign(name="QA · adjunto "+kind,objective="Validar un adjunto a mi propio chat.",mode="AUTO",recipients=recipient,dailyLimit=1,quietStart=0,quietEnd=0)
        g.dao.save(c)
        val asset=if(kind=="image")g.assets.createImage(c.id,"Prueba de Campaña","Imagen creada y enviada desde el teléfono.") else {
            val fileName=newId()+".pdf";val file=File(context.filesDir,"assets/"+fileName)
            val pdf=PdfDocument()
            try {
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create())
                page.canvas.drawText("Prueba local de Campana 0.1.0",40f,80f,Paint().apply { textSize=20f })
                pdf.finishPage(page);file.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            Asset(campaignId=c.id,name="Prueba-local-Campana.pdf",mime="application/pdf",fileName=fileName).also { g.dao.insert(it) }
        }
        val caption="Prueba local de Campaña: adjunto "+kind+"."
        val task=AgentTask(campaignId=c.id,channel="WHATSAPP",kind="ATTACHMENT",recipient=recipient,assetId=asset.id,
            instruction="Usa share_attachment para enviar el archivo de esta tarea únicamente a "+recipient+
                ". Usa exactamente este caption público: "+caption+
                " En el selector elige solo mi chat, comprueba que hay 1 seleccionado y que recipients muestra mi nombre. Pulsa Enviar una sola vez y verifica el resultado. No uses cámara, galería ni otros contactos.")
        try {
            g.dao.insert(task);g.engine.runTask(task.id)
            val result=g.dao.task(task.id)!!
            assertEquals("Resultado: "+result.detail,"SUBMITTED",result.status)
            val after=withContext(Dispatchers.Main) { service.observe() }
            assertTrue("El adjunto debe aparecer en el chat.",after.nodes.any { !it.editable && (it.text==caption || (kind=="pdf" && it.text.contains(asset.name))) })
            assertEquals(1,g.dao.allReceipts(c.id).count { it.action=="external_submit" })
        } finally {
            g.prefs.edit().putBoolean("ui_enabled",previousUi).commit();g.pause(previousPause || g.paused.value)
            g.dao.save(c.copy(mode="DRAFT"))
        }
    }
    @Test fun agentSendsOneMessageToVerifiedSelfChat()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit self-chat test arguments.",args.getString("self")=="true")
        val recipient=args.getString("recipient") ?: error("Indica la cabecera del chat propio.")
        require(recipient.endsWith("(Tú)") || recipient.endsWith("(You)")) { "La prueba solo admite el chat propio." }
        val context=ApplicationProvider.getApplicationContext<Context>()
        val g=(context.applicationContext as CampaignApp).graph;g.ready.await()
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component=context.packageName+"/org.campana.agent.phone.PhoneAccessibilityService"
        val before=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        require(component in before.split(":") && before.matches(Regex("[A-Za-z0-9_.$:/]*")))
        fun shell(command:String) { automation.executeShellCommand(command).use { p->java.io.FileInputStream(p.fileDescriptor).use { it.readBytes() } } }
        val remaining=before.split(":").filter { it!=component }.joinToString(":")
        shell(if(remaining.isEmpty())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services "+remaining)
        delay(1200)
        shell("settings put secure enabled_accessibility_services "+before)
        shell("settings put secure accessibility_enabled 1")
        val until=SystemClock.elapsedRealtime()+8000
        while(PhoneAccessibilityService.instance==null && SystemClock.elapsedRealtime()<until)delay(100)
        assertNotNull(PhoneAccessibilityService.instance)
        val initial=withContext(Dispatchers.Main) { PhoneAccessibilityService.instance!!.observe() }
        assertEquals("com.whatsapp",initial.packageName)
        assertTrue(initial.nodes.any { it.resource=="com.whatsapp:id/conversation_contact_name" && it.text==recipient })
        val previousUi=g.prefs.getBoolean("ui_enabled",false);val previousPause=g.paused.value
        g.prefs.edit().putBoolean("ui_enabled",true).commit();g.pause(false)
        val c=Campaign(name="QA · envío a mi propio chat",objective="Validar un único mensaje desde el teléfono.",
            mode="AUTO",recipients=recipient,dailyLimit=1,quietStart=0,quietEnd=0)
        g.dao.save(c)
        val text="Prueba de Campaña 0.1.0: este mensaje fue preparado y enviado por el agente desde el celular."
        val task=AgentTask(campaignId=c.id,channel="WHATSAPP",kind="MESSAGE",recipient=recipient,
            instruction="El chat propio ya está abierto. Observa la pantalla, escribe exactamente este texto y pulsa Enviar una sola vez: "+text+
                " No abras otra conversación. No adjuntes nada. Observa el mensaje resultante y termina.")
        try {
            g.dao.insert(task);g.engine.runTask(task.id)
            val result=g.dao.task(task.id)!!
            assertEquals("Resultado: "+result.detail,"SUBMITTED",result.status)
            val state=withContext(Dispatchers.Main) { PhoneAccessibilityService.instance!!.observe() }
            assertTrue("El texto debe aparecer como mensaje y no solo como borrador.",state.nodes.any { !it.editable && it.text==text })
            assertEquals(1,g.dao.allReceipts(c.id).count { it.action=="external_submit" })
        } finally {
            g.prefs.edit().putBoolean("ui_enabled",previousUi).commit();g.pause(previousPause || g.paused.value)
            // Keep the authorized test campaign and its receipts as evidence; no automatic retry.
            g.dao.save(c.copy(mode="DRAFT",autoReply=false))
        }
    }
}
