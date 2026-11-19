package org.campana.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Before
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.runBlocking

class CampaignUiTest {
    private val campaignName="QA · Campaña local "+System.currentTimeMillis()
    private var previousCampaign=""
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun rememberSelection()=runBlocking{val graph=(compose.activity.application as CampaignApp).graph;graph.ready.await();previousCampaign=graph.activeCampaign.value}
    @Test fun navigationAndCampaignFormAreAvailable() {
        compose.onNode(hasText("Campañas") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).performClick()
        compose.onNodeWithContentDescription("Opciones de campañas").performClick()
        compose.onNodeWithText("Crear con formulario").performClick()
        compose.onNodeWithText("Nombre de campaña").performTextInput(campaignName)
        compose.onNodeWithText("Objetivo").performTextInput("Comprobar administración desde Android")
        compose.runOnUiThread{compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)}
        compose.waitForIdle()
        // Samsung's native IME close animation is outside Compose's idling resources.
        android.os.SystemClock.sleep(350)
        compose.onNodeWithText(campaignName).assertExists()
        compose.onNodeWithText("Comprobar administración desde Android").assertExists()
        compose.onNodeWithText("Guardar campaña").performClick()
        val graph=(compose.activity.application as CampaignApp).graph
        try { compose.waitUntil(5_000) { runBlocking { graph.dao.campaigns().any { it.name==campaignName } } } }
        catch(e:Exception) { compose.onRoot().printToLog("QA_FORM");throw e }
        compose.waitUntil(5_000){compose.onAllNodesWithText(campaignName).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(campaignName).assertExists()
        compose.onNodeWithText("Hablar con el agente").performClick()
        compose.onNodeWithText("¿Qué hacemos con esta campaña?").assertExists()
        compose.onNode(hasText("Ajustes") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).performClick()
        compose.onNodeWithText("Cuenta de ChatGPT").assertExists()
    }
    @After fun removeOnlyTestCampaign()=runBlocking {
        val graph=(compose.activity.application as CampaignApp).graph
        graph.dao.campaigns().filter { it.name==campaignName }.forEach { graph.dao.deleteCampaign(it.id) }
        graph.selectCampaign(previousCampaign)
    }
}
