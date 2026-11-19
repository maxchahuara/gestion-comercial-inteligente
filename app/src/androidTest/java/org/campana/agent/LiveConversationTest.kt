package org.campana.agent

import android.app.UiAutomation
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.campana.agent.data.newId
import org.junit.*
import java.io.File

/** Explicit opt-in: actual ChatGPT, fixture business only, no social tools or submissions. */
class LiveConversationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun createAndEditCampaignByConversationOnPhone() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveConversation")=="true")
        val g=(compose.activity.application as CampaignApp).graph
        runBlocking {g.ready.await()}
        Assume.assumeTrue(g.auth.vault.active()!=null && g.model.value.isNotBlank())
        // Do not resume if any real task might become runnable during this test.
        Assume.assumeFalse(runBlocking {g.dao.watchTasks().first().any{it.status in setOf("QUEUED","WAITING_NETWORK","WAITING_UNLOCK","WAITING_PERMISSION") && it.dueAt<System.currentTimeMillis()+300000}})
        val previousId=g.activeCampaign.value;val previousPause=g.paused.value
        val marker="QA Conversación "+newId().take(8)
        val before=runBlocking{g.dao.campaigns().map{it.id}.toSet()}
        var createdId:String?=null
        try {
            g.pause(true)
            compose.onNode(hasText("Campañas") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).performClick()
            compose.onNodeWithText("Crear campaña",useUnmergedTree=true).performClick()
            g.pause(false)
            val first="Prueba local: crea la campaña '$marker' para promocionar clases de cerámica a principiantes. Objetivo: conseguir consultas. Oferta: taller de 2 horas por 50 soles. Guarda el nombre, el objetivo y la oferta en la campaña actual. Mantén borradores. No crees tareas ni abras otras apps. Dime qué guardaste."
            compose.onNode(hasSetTextAction()).performTextInput(first)
            compose.onNodeWithContentDescription("Enviar instrucción").performClick()
            compose.waitUntil(10000){g.activeCampaign.value !in before}
            createdId=g.activeCampaign.value
            compose.waitUntil(150000){!g.busy.value && !g.conversations.submitting.value}
            val c=runBlocking{g.dao.campaign(createdId!!)!!}
            Assert.assertEquals(marker,c.name)
            Assert.assertTrue(c.objective.isNotBlank());Assert.assertTrue(c.offer.contains("50"))
            Assert.assertEquals("DRAFT",c.mode)
            Assert.assertTrue(runBlocking{g.dao.allMessages(c.id).none{it.role=="error"}})
            capture("04-campana-creada-con-chat")
            compose.onNode(hasSetTextAction()).performTextInput("Cambia solo el tono de esta campaña a 'Cercano y didáctico'. Conserva nombre, objetivo, oferta y todas las reglas. Guarda el cambio sin crear tareas.")
            compose.onNodeWithContentDescription("Enviar instrucción").performClick()
            compose.waitUntil(10000){g.conversations.submitting.value || g.busy.value}
            compose.waitUntil(150000){!g.busy.value && !g.conversations.submitting.value}
            val after=runBlocking{g.dao.campaign(c.id)!!}
            Assert.assertTrue(after.tone.contains("didáctico",ignoreCase=true))
            Assert.assertEquals(c.name,after.name);Assert.assertEquals(c.objective,after.objective);Assert.assertEquals(c.offer,after.offer)
            Assert.assertEquals("DRAFT",after.mode);Assert.assertTrue(runBlocking{g.dao.tasks(c.id).isEmpty()})
            Assert.assertTrue(runBlocking{g.dao.allReceipts(c.id).none{it.action=="external_submit"}})
            capture("05-cambio-conversacional")
        } finally {
            g.pause(true)
            runBlocking {createdId?.let{g.dao.deleteCampaign(it)}}
            g.selectCampaign(previousId);g.pause(previousPause)
        }
    }
    private fun capture(name:String) {
        compose.runOnUiThread {compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)}
        compose.waitForIdle();SystemClock.sleep(300)
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        check(automation.rootInActiveWindow?.packageName?.toString()=="org.campana.agent")
        val image=checkNotNull(automation.takeScreenshot())
        val folder=File(compose.activity.getExternalFilesDir(null),"chat-qa").apply{mkdirs()}
        File(folder,name+".png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
