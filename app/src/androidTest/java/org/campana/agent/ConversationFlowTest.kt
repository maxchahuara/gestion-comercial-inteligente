package org.campana.agent

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.campana.agent.agent.*
import org.campana.agent.data.*
import org.campana.agent.net.json
import org.junit.*
import java.io.File

class ConversationFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var g:AppGraph
    private lateinit var campaign:Campaign
    private var previousId="";private var previousPause=false
    private val marker="Chat UX · "+System.currentTimeMillis()
    @Before fun prepareOwnedDraft() {
        runBlocking {
        g=(compose.activity.application as CampaignApp).graph;g.ready.await()
        previousId=g.activeCampaign.value;previousPause=g.paused.value;g.pause(true);g.status.value=""
        campaign=Campaign(name=marker,objective="Revisar conversación sin envíos",recipients="Contacto de laboratorio")
        g.dao.save(campaign);g.selectCampaign(campaign.id)
        }
        tab("Agente")
        compose.waitUntil(6000){compose.onAllNodesWithText(campaign.name).fetchSemanticsNodes().isNotEmpty() && !g.busy.value && !g.conversations.submitting.value}
    }
    @After fun removeOnlyOwnedFixtures()=runBlocking {
        if(::g.isInitialized) {
            g.dao.campaigns().filter { it.name.contains(marker) }.forEach { g.dao.deleteCampaign(it.id) }
            g.selectCampaign(previousId);g.pause(previousPause)
        }
    }
    private fun tab(text:String) {
        compose.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).performClick()
        compose.waitForIdle()
    }
    private fun click(text:String) {
        try {compose.waitUntil(6000){compose.onAllNodesWithText(text,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
        catch(e:Throwable){capture("failure-click");compose.onRoot().printToLog("CHAT_CLICK");throw e}
        val unmerged=compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        val node=compose.onNodeWithText(text,useUnmergedTree=unmerged);runCatching{node.performScrollTo()};node.assertIsEnabled();node.performClick();compose.waitForIdle()
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("chatCaptures")!="true")return
        // Native dialog/IME animations are outside Compose idling; capture their settled appearance.
        compose.waitForIdle();SystemClock.sleep(350)
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        check(automation.rootInActiveWindow?.packageName?.toString()=="org.campana.agent")
        val image=checkNotNull(automation.takeScreenshot())
        val folder=File(compose.activity.getExternalFilesDir(null),"chat-qa").apply{mkdirs()}
        File(folder,name+".png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun newCampaignStartsInChatWithoutCreatingAnEmptyRecord() {
        val before=runBlocking{g.dao.campaigns().size}
        tab("Campañas");click("Crear campaña")
        compose.onNodeWithText("Cuéntame qué quieres conseguir").assertIsDisplayed()
        compose.onNodeWithText("Nombre de campaña").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Quiero vender mis productos en una campaña de prueba")
        compose.runOnUiThread { compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0) }
        compose.waitForIdle();SystemClock.sleep(250);capture("01-nueva-conversacion")
        tab("Ajustes");tab("Agente")
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Quiero vender mis productos en una campaña de prueba")))
        click("Nueva campaña");click(campaign.name)
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithContentDescription("Opciones de la conversación").performClick();click("Nueva campaña por chat")
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Quiero vender mis productos en una campaña de prueba")))
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Quiero vender mis productos en una campaña de prueba")))
        Assert.assertEquals(before,runBlocking{g.dao.campaigns().size})
        Assert.assertEquals(campaign.id,g.activeCampaign.value)
    }
    @Test fun rulesNeedHumanReviewAndDoNotRunExistingDraftsOrResume() {
        val task=AgentTask(campaignId=campaign.id,channel="WHATSAPP",instruction="Solo fixture local",status="DRAFT")
        runBlocking {
            g.dao.insert(task)
            g.conversations.propose(campaign.id,CampaignRules.from(campaign).copy(mode="AUTO",dailyLimit=2))
        }
        tab("Agente");click("Revisar propuesta")
        compose.waitUntil(6000){compose.onAllNodesWithText("Revisar autorización").fetchSemanticsNodes().isNotEmpty()}
        capture("02-revision-reglas")
        Assert.assertEquals("DRAFT",runBlocking{g.dao.campaign(campaign.id)!!.mode})
        compose.onNodeWithText("Puede escribir a: Contacto de laboratorio",substring=true).assertExists()
        click("Autorizar estas reglas")
        compose.waitUntil(5000){runBlocking{g.dao.campaign(campaign.id)?.mode=="AUTO"}}
        Assert.assertTrue(g.paused.value)
        Assert.assertEquals("DRAFT",runBlocking{g.dao.task(task.id)!!.status})
        Assert.assertEquals(1,runBlocking{g.dao.allReceipts(campaign.id).count{it.status=="HUMAN_APPROVED"}})
        capture("03-reglas-confirmadas")
    }
    @Test fun staleRuleProposalCannotOverwriteNewerHumanRules() {
        runBlocking { g.conversations.propose(campaign.id,CampaignRules.from(campaign).copy(mode="AUTO",dailyLimit=2)) }
        tab("Agente");click("Revisar propuesta")
        runBlocking { g.dao.save(campaign.copy(dailyLimit=7)) }
        click("Autorizar estas reglas")
        compose.waitUntil(5000){runBlocking{g.dao.allMessages(campaign.id).all{it.role!="rules_proposal"}}}
        val current=runBlocking{g.dao.campaign(campaign.id)!!}
        Assert.assertEquals("DRAFT",current.mode);Assert.assertEquals(7,current.dailyLimit)
        Assert.assertTrue(runBlocking{g.dao.allReceipts(campaign.id).isEmpty()})
    }
    @Test fun discardingAProposalPreservesExistingAutomaticRules() {
        val automatic=campaign.copy(mode="AUTO",dailyLimit=3)
        runBlocking {g.dao.save(automatic);g.conversations.propose(campaign.id,CampaignRules.from(automatic).copy(mode="DRAFT"))}
        tab("Agente");click("Descartar propuesta")
        compose.waitUntil(5000){runBlocking{g.dao.allMessages(campaign.id).all{it.role!="rules_proposal"}}}
        Assert.assertEquals(CampaignRules.from(automatic),CampaignRules.from(runBlocking{g.dao.campaign(campaign.id)!!}))
    }
    @Test fun explicitSelectionFromActivityLeavesThePendingNewConversation() {
        val other=campaign.copy(id=newId(),name=marker+" · B")
        runBlocking {g.dao.save(other)}
        tab("Campañas");click("Crear campaña")
        tab("Actividad");click(campaign.name);click(other.name);tab("Agente")
        compose.onNodeWithText(other.name).assertExists()
        compose.onNodeWithText("Cuéntame qué quieres conseguir").assertDoesNotExist()
    }
    @Test fun longDestinationsRemainReadableAndReviewActionsStayAccessible() {
        val names=(1..12).map { "Contacto de laboratorio $it con nombre completo para comprobar la revisión móvil" }
        runBlocking {g.conversations.propose(campaign.id,CampaignRules.from(campaign).copy(mode="AUTO",recipients=names.joinToString("\n")))}
        tab("Agente");click("Revisar propuesta")
        compose.waitUntil(6000){compose.onAllNodesWithText("Revisar autorización").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(names.last()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Autorizar estas reglas").assertIsDisplayed().assertIsEnabled()
        capture("06-destinos-completos")
        click("Volver al chat")
        Assert.assertEquals("DRAFT",runBlocking{g.dao.campaign(campaign.id)!!.mode})
    }
    @Test fun conversationalToolsAreScopedAndNeverApproveThemselves()=runBlocking {
        // A private in-memory graph avoids changing the real account, pause state or work queue.
        val context=compose.activity.applicationContext
        val preferences="chat-tools-"+newId()
        val isolated=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(preferences+name,mode)
        }
        val db=Room.inMemoryDatabaseBuilder(context,CampaignDatabase::class.java).build()
        val local=AppGraph(isolated,db)
        try {
            local.ready.await()
            val c=campaign.copy(offer="Oferta que debe conservarse",knowledge="Información aprobada")
            val other=c.copy(id=newId(),name="Otra campaña")
            local.dao.save(c);local.dao.save(other)
            val tools=Toolbox(local)
            tools.execute("set_brief_field",buildJsonObject{put("field","objective");put("value","Objetivo actualizado")},c.id,null)
            val current=local.dao.campaign(c.id)!!
            Assert.assertEquals(c.offer,current.offer);Assert.assertEquals(c.knowledge,current.knowledge)
            Assert.assertEquals(CampaignRules.from(c),CampaignRules.from(current));Assert.assertEquals(other,local.dao.campaign(other.id))
            val proposed=CampaignRules.from(c).copy(mode="AUTO",dailyLimit=2)
            val params=buildJsonObject {
                put("channels",proposed.channels);put("mode",proposed.mode);put("recipients",proposed.recipients);put("account_identity",proposed.accountIdentity)
                put("daily_limit",proposed.dailyLimit);put("quiet_start",proposed.quietStart);put("quiet_end",proposed.quietEnd)
                put("auto_reply",proposed.autoReply);put("allow_publishing",proposed.allowPublishing)
            }
            tools.execute("propose_rules",params,c.id,null)
            Assert.assertEquals("DRAFT",local.dao.campaign(c.id)!!.mode)
            val proposal=local.dao.allMessages(c.id).single{it.role=="rules_proposal"}
            try {local.conversations.resolve(proposal.id,other.id,true);Assert.fail("No debe aprobar otra campaña")}catch(_:IllegalArgumentException){}
            local.conversations.propose(c.id,CampaignRules.from(current))
            Assert.assertTrue(local.dao.allMessages(c.id).none{it.role=="rules_proposal"})
            try {local.conversations.resolve(proposal.id,c.id,true);Assert.fail("No debe aplicar la propuesta retirada")}catch(_:IllegalArgumentException){}
            Assert.assertEquals("DRAFT",local.dao.campaign(c.id)!!.mode)
            val uncertain=AgentTask(campaignId=c.id,channel="WHATSAPP",instruction="Sin efecto real",status="UNCERTAIN")
            local.dao.insert(uncertain)
            tools.execute("cancel_task",buildJsonObject{put("task_id",uncertain.id)},c.id,null)
            Assert.assertEquals("UNCERTAIN",local.dao.task(uncertain.id)!!.status)
            try {tools.execute("resume_pending_task",buildJsonObject{put("task_id",uncertain.id)},c.id,null);Assert.fail("No debe reintentar un resultado incierto")}catch(_:IllegalArgumentException){}
            val attempted=uncertain.copy(id=newId(),status="WAITING_OWNER")
            local.dao.insert(attempted)
            local.dao.insert(Receipt(campaignId=c.id,taskId=attempted.id,action="external_submit",status="SUBMITTING",detail="Fixture sin efecto externo"))
            try {tools.execute("resume_pending_task",buildJsonObject{put("task_id",attempted.id)},c.id,null);Assert.fail("No debe repetir un intento registrado")}catch(_:IllegalStateException){}
            Assert.assertEquals("WAITING_OWNER",local.dao.task(attempted.id)!!.status)
            try {tools.execute("set_brief_field",buildJsonObject{put("field","mode");put("value","AUTO")},c.id,null);Assert.fail("No debe ampliar permisos")}catch(_:IllegalStateException){}
        } finally {local.scope.cancel();db.close();context.deleteSharedPreferences(preferences+"settings")}
    }
    @Test fun draftCreationIsIdempotentAcrossRetriesAndDoesNotTouchOtherCampaigns()=runBlocking {
        val id=newId()
        try {
            val before=g.dao.campaign(campaign.id)
            val first=g.conversations.ensureDraft(id)
            val second=g.conversations.ensureDraft(id)
            Assert.assertEquals(first,second);Assert.assertEquals("DRAFT",first.mode)
            Assert.assertFalse(first.autoReply);Assert.assertFalse(first.allowPublishing)
            Assert.assertEquals(before,g.dao.campaign(campaign.id))
        } finally {g.dao.deleteCampaign(id)}
    }
}
