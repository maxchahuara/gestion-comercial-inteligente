package org.campana.agent

import android.app.UiAutomation
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.campana.agent.data.*
import org.campana.agent.net.json
import org.campana.agent.ui.*
import org.junit.*
import java.io.File

class UxFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var g:AppGraph
    private lateinit var campaign:Campaign
    private lateinit var second:Campaign
    private var previousCampaign="";private var previousModel="";private var previousPause=false
    private var previousRotation=0;private var previousAutoRotation=1
    private val tag=System.currentTimeMillis().toString()
    private val createdFiles=mutableListOf<File>()
    private val shots=InstrumentationRegistry.getArguments().getString("uxCaptures")=="true"
    private val captureSet=InstrumentationRegistry.getArguments().getString("uxCaptureSet","after").also{require(it.matches(Regex("[a-z-]+")))}
    @Before fun prepareLocalOnlyCampaigns() {
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(if(compose.activity.packageName.endsWith(".visualqa"))0 else UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=compose.activity.contentResolver
        previousRotation=android.provider.Settings.System.getInt(resolver,android.provider.Settings.System.USER_ROTATION,0)
        previousAutoRotation=android.provider.Settings.System.getInt(resolver,android.provider.Settings.System.ACCELEROMETER_ROTATION,1)
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        compose.waitUntil(6000){runCatching{compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}.getOrDefault(false)}
        runBlocking {
        g=(compose.activity.application as CampaignApp).graph;g.ready.await()
        previousCampaign=g.activeCampaign.value;previousModel=g.model.value;previousPause=g.paused.value;g.pause(true);g.status.value=""
        campaign=Campaign(name="Revisión UX · "+tag,objective="Preparar borradores para comprobar la interfaz. No realizar envíos.",channels="WHATSAPP,INSTAGRAM",recipients="Contacto de ejemplo",updatedAt=System.currentTimeMillis()+2)
        second=campaign.copy(id=newId(),name="Otra campaña UX · "+tag,updatedAt=System.currentTimeMillis()-1)
        g.dao.save(campaign);g.dao.save(second);g.selectCampaign(campaign.id)
        val asset=g.assets.createImage(campaign.id,"Ejemplo de revisión UX","Recurso local de prueba. No publicar.");createdFiles+=g.assets.file(asset)
        g.dao.insert(AgentTask(campaignId=campaign.id,channel="WHATSAPP",kind="MESSAGE",instruction="Mensaje de ejemplo para revisar la interfaz; no enviar.",recipient="Contacto de ejemplo",status="DRAFT"))
        g.dao.insert(AgentTask(campaignId=campaign.id,channel="WHATSAPP",kind="ATTACHMENT",instruction="Ejemplo de estado incierto para revisar la interfaz. No hubo un envío real.",recipient="Contacto de ejemplo",assetId=asset.id,status="UNCERTAIN",detail="Ejemplo controlado de revisión UX; no representa una acción externa."))
        g.dao.insert(Receipt(campaignId=campaign.id,action="qa_render_fixture",status="OBSERVED",detail="""{"result":"{\"error\":\"Ejemplo de error para revisar la interfaz; no se ejecutó una herramienta.\"}"}"""))
        }
        tab("Campañas")
        try {
            compose.waitUntil(8000){
                runCatching{compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).performScrollToIndex(0)}
                compose.onAllNodesWithText(campaign.name).fetchSemanticsNodes().isNotEmpty()
            }
        } catch(e:Exception) { capture("failure-setup");compose.onRoot().printToLog("UX_SETUP");throw e }
    }
    @After fun removeOnlyOwnedFixtures()=runBlocking {
        if(::g.isInitialized) {
            g.dao.campaigns().filter{it.name.contains(tag)}.forEach { c->
                g.dao.assets(c.id).forEach{runCatching{g.assets.file(it).delete()}};g.dao.deleteCampaign(c.id)
            }
            createdFiles.forEach{it.delete()};g.selectCampaign(previousCampaign);g.selectModel(previousModel);g.pause(previousPause)
        }
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(if(compose.activity.packageName.endsWith(".visualqa"))0 else UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.setRotation(previousRotation);if(previousAutoRotation==1)automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }
    private fun tab(label:String) {
        hideKeyboard()
        compose.waitUntil(6000){runCatching{compose.onAllNodes(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).performClick()
        compose.waitForIdle()
    }
    private fun click(label:String) {
        try {compose.waitUntil(6000){runCatching{compose.onAllNodesWithText(label,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}}
        catch(e:Throwable){compose.onRoot().printToLog("UX_CLICK_STATE");runCatching{capture("failure-click")};throw AssertionError("No apareció el control: "+label,e)}
        val merged=compose.onAllNodesWithText(label).fetchSemanticsNodes()
        val node=compose.onNodeWithText(label,useUnmergedTree=merged.isEmpty())
        runCatching{node.performScrollTo()};node.performClick()
        compose.waitForIdle()
    }
    private fun back(){hideKeyboard();compose.runOnUiThread{compose.activity.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    private fun hideKeyboard(){
        compose.runOnUiThread{
            val focused=android.view.inspector.WindowInspector.getGlobalWindowViews().firstOrNull{it.hasWindowFocus()}
            val token=focused?.windowToken ?: compose.activity.window.decorView.windowToken
            compose.activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(token,0)
        }
        compose.waitForIdle();SystemClock.sleep(180)
    }
    private fun capture(name:String) {
        if(!shots)return
        // Capturing is read-only: never send Back or alter the dialog/keyboard being inspected.
        compose.waitForIdle();SystemClock.sleep(250)
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(if(compose.activity.packageName.endsWith(".visualqa"))0 else UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Window focus may still be returning from the keyboard or a native popup.
        compose.waitUntil(4000){automation.rootInActiveWindow?.packageName?.toString()==compose.activity.packageName}
        check(automation.rootInActiveWindow?.packageName?.toString()==compose.activity.packageName) { "Capture limited to Campaña; active package: "+automation.rootInActiveWindow?.packageName }
        val bitmap=checkNotNull(automation.takeScreenshot())
        val folder=File(compose.activity.getExternalFilesDir(null),"ux-audit/"+captureSet).apply{mkdirs()}
        File(folder,name+".png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun campaignValidationAndChangesSurviveRecreation() {
        capture("01-campaigns")
        compose.onNodeWithContentDescription("Opciones de campañas").performClick();capture("09b-campaign-menu")
        val popupAutomation=InstrumentationRegistry.getInstrumentation().getUiAutomation(if(compose.activity.packageName.endsWith(".visualqa"))0 else UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Compose idle does not include the native popup's input-focus transition.
        compose.onNodeWithText("Restaurar una copia").assertIsDisplayed();SystemClock.sleep(300)
        popupAutomation.executeShellCommand("input keyevent 4").use{p->java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}}
        compose.waitForIdle()
        click(campaign.name);capture("01b-campaign-detail")
        click("Editar contexto");capture("02a-editor-context")
        tab("Canales");capture("02b-editor-channels")
        tab("Reglas");capture("02c-editor-rules")
        compose.onNodeWithText("Permitir publicaciones").performScrollTo();capture("02f-editor-rules-bottom")
        back();back()
        compose.onNodeWithContentDescription("Opciones de campañas").performClick();click("Crear con formulario");click("Guardar campaña")
        compose.onNodeWithText("Escribe un nombre para identificar la campaña.").assertExists();capture("02d-required-errors")
        compose.onNodeWithText("Nombre de campaña").performTextInput("Nueva UX · "+tag)
        compose.onNodeWithText("Objetivo").performTextInput("Conservar este objetivo sin ejecutar ninguna acción.")
        hideKeyboard();tab("Canales");tab("Contexto")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000){compose.onAllNodesWithText("Nueva UX · "+tag).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Nueva UX · "+tag).assertExists()
        back();compose.onNodeWithText("¿Descartar los cambios?").assertExists();capture("02e-discard-dialog")
        click("Seguir editando");click("Guardar campaña")
        compose.waitUntil(5000){runBlocking{g.dao.campaigns().any{it.name=="Nueva UX · "+tag}}}
        val saved=runBlocking{g.dao.campaigns().first{it.name=="Nueva UX · "+tag}}
        Assert.assertEquals("DRAFT",saved.mode)
    }
    @Test fun taskUsesReadableDateAndSavesOnlyDraft() {
        tab("Actividad");capture("04a-tasks")
        click("Nueva tarea");capture("05a-task-content");click("Guardar borrador")
        compose.onNodeWithText("Describe lo que debe preparar el agente.").assertExists()
        compose.onNodeWithText("Instrucción para el agente").performTextInput("Preparar una propuesta para la revisión UX, no enviar.")
        hideKeyboard();click("Elegir destinatario");click("Contacto de ejemplo")
        tab("Cuándo");click("Más tarde");capture("05b-task-schedule")
        click("Guardar borrador")
        compose.waitUntil(5000){runBlocking{g.dao.tasks(campaign.id).any{it.instruction.startsWith("Preparar una propuesta")}}}
        val task=runBlocking{g.dao.tasks(campaign.id).first{it.instruction.startsWith("Preparar una propuesta")}}
        Assert.assertEquals("DRAFT",task.status);Assert.assertTrue(task.dueAt>System.currentTimeMillis())
        capture("05d-task-draft-detail")
        compose.onNodeWithText("Revisar reglas").assertExists()
        compose.onNodeWithText("Revisar y ejecutar").assertDoesNotExist()
    }
    @Test fun uncertainResultHasNoRetryOrUndoAction() {
        tab("Actividad");click("Revisar resultado")
        compose.onNodeWithText("Destino y momento").assertExists();capture("05c-task-uncertain")
        compose.onNodeWithText("Revisar y ejecutar").assertDoesNotExist()
        compose.onNodeWithText("Cancelar tarea pendiente").assertDoesNotExist()
        back();tab("Registro");capture("04b-records")
        compose.onNodeWithText("No se completó la acción").assertExists()
        click("Detalles técnicos");capture("04b2-records-technical")
    }
    @Test fun interestedContactIsEditableWithoutCallingTheAgent() {
        tab("Actividad");tab("Interesados");capture("04c-leads-empty")
        click("Registrar interesado");capture("04d-lead-editor")
        compose.onNodeWithText("Nombre").performTextInput("Contacto UX · "+tag)
        hideKeyboard();click("Guardar interesado")
        compose.waitUntil(5000){runBlocking{g.dao.leads(campaign.id).isNotEmpty()}}
        val lead=runBlocking{g.dao.leads(campaign.id).single()}
        Assert.assertEquals("Contacto UX · "+tag,lead.name)
        Assert.assertTrue(runBlocking{g.dao.history(campaign.id).isEmpty()})
        Assert.assertEquals("Contacto de ejemplo",runBlocking{g.dao.campaign(campaign.id)!!.recipients})
        compose.waitUntil(5000){compose.onAllNodesWithText(lead.name).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("Interesados") and SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab)).assertIsSelected()
        capture("04e-leads-populated")
        click("Ver y editar")
        compose.onNodeWithText("Nombre").performTextReplacement("Contacto actualizado · "+tag)
        hideKeyboard();click("Guardar interesado")
        compose.waitUntil(5000){compose.onAllNodesWithText("Contacto actualizado · "+tag).fetchSemanticsNodes().isNotEmpty()}
        Assert.assertEquals(lead.id,runBlocking{g.dao.leads(campaign.id).single()}.id)
    }
    @Test fun chatDraftsStayWithTheirCampaignAndSurviveRotation() {
        tab("Agente");capture("03a-agent-empty")
        compose.onNode(hasSetTextAction()).performTextInput("Borrador local para esta campaña")
        hideKeyboard();click(campaign.name);capture("09a-campaign-selector");click(second.name)
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        click(second.name);click(campaign.name)
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Borrador local para esta campaña")))
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("Borrador local para esta campaña")))
        capture("03b-agent-draft")
    }
    @Test fun settingsHaveWorkingBackAndPermissionReview() {
        tab("Ajustes");capture("06a-settings")
        click("Cuenta de ChatGPT");capture("06b-account")
        back();click("Permisos y control");capture("06c-permissions")
        click("Revisar avisos del agente")
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(if(compose.activity.packageName.endsWith(".visualqa"))0 else UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val deadline=SystemClock.elapsedRealtime()+8000
        while(automation.rootInActiveWindow?.packageName?.toString()!="com.android.settings" && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        Assert.assertEquals("com.android.settings",automation.rootInActiveWindow?.packageName?.toString())
        repeat(3){
            if(automation.rootInActiveWindow?.packageName?.toString()!="org.campana.agent") {
                automation.executeShellCommand("input keyevent 4").use{p->java.io.FileInputStream(p.fileDescriptor).use{it.readBytes()}}
                SystemClock.sleep(500)
            }
        }
        Assert.assertEquals("org.campana.agent",automation.rootInActiveWindow?.packageName?.toString())
        back();click("Aplicaciones");capture("06d-applications")
        back();click("Ayuda y diagnóstico");capture("06e-help")
    }
    @Test fun cobaltSettingsAndPermissionExplanationStayInsideTheApp() {
        check(compose.activity.packageName.endsWith(".visualqa"))
        tab("Ajustes");capture("06a-settings")
        click("Cuenta de ChatGPT");capture("06b-account")
        back();click("Permisos y control");capture("06c-permissions")
        compose.onNodeWithText("No puedo activar un permiso").performScrollTo();click("No puedo activar un permiso")
        capture("06c2-permission-help");click("Entendido")
        back();click("Aplicaciones");capture("06d-applications")
        back();click("Ayuda y diagnóstico");capture("06e-help")
        Assert.assertTrue(g.paused.value)
        Assert.assertFalse(g.prefs.getBoolean("ui_enabled",false))
    }
    @Test fun cobaltConversationAndPendingAuthorizationKeepTheirMeaning() {
        check(compose.activity.packageName.endsWith(".visualqa"))
        val rules=org.campana.agent.agent.CampaignRules.from(campaign)
        val proposal=org.campana.agent.agent.RulesProposal(rules,rules.copy(mode="REACTIVE",channels="WHATSAPP",recipients="*",autoReply=true,allowPublishing=false))
        val time=System.currentTimeMillis()
        runBlocking {
            g.dao.insert(ChatMessage(campaignId=campaign.id,role="user",text="Prepara contenido para presentar mi negocio. Quiero revisarlo antes de enviar.",createdAt=time))
            g.dao.insert(ChatMessage(campaignId=campaign.id,role="assistant",text="Prepararé una propuesta con la información de esta campaña.\n\n**Primero**, revisa el texto y los destinatarios. Los envíos siguen en borradores.",createdAt=time+1))
            g.dao.insert(ChatMessage(campaignId=campaign.id,role="rules_proposal",text=json.encodeToString(proposal),createdAt=time+2))
        }
        tab("Agente");click("Revisar propuesta");capture("03d-rules-review")
        Assert.assertEquals("DRAFT",runBlocking{g.dao.campaign(campaign.id)!!.mode})
        click("Volver al chat");capture("03c-rules-proposed")
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).performScrollToIndex(0);capture("03c2-conversation")
        runBlocking{g.dao.insert(ChatMessage(campaignId=campaign.id,role="error",text="Ejemplo de error de conexión para revisar la interfaz. No se ejecutó ninguna acción externa.",createdAt=time+3))}
        compose.waitUntil(6000){compose.onAllNodesWithText("No se completó la respuesta").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Ver mensajes nuevos").fetchSemanticsNodes().isNotEmpty()}
        if(compose.onAllNodesWithText("Ver mensajes nuevos").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithText("Ver mensajes nuevos").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        compose.onNodeWithText("Recuperar texto para editar").performScrollTo();capture("03e-conversation-error")
        Assert.assertTrue(g.paused.value)
        Assert.assertEquals("DRAFT",runBlocking{g.dao.campaign(campaign.id)!!.mode})
    }
    @Test fun editingAnotherCampaignKeepsSelectedContext() {
        // Establish the tested destination after setup/rotation has settled. The
        // selected campaign name also exists in Agent, so it cannot prove this tab.
        tab("Campañas")
        click(second.name);click("Editar contexto")
        compose.onNodeWithText(second.name).assertExists()
        compose.onNodeWithText("Objetivo").performTextReplacement("Objetivo editado localmente para la revisión UX.")
        compose.onNodeWithText("Objetivo editado localmente para la revisión UX.").assertExists()
        capture("02g-other-campaign-before-save")
        hideKeyboard();click("Guardar campaña")
        try {compose.waitUntil(5000){runBlocking{g.dao.campaign(second.id)?.objective?.startsWith("Objetivo editado")==true}}}
        catch(e:Throwable){compose.onRoot().printToLog("UX_SAVE_STATE");capture("failure-other-campaign-save");throw e}
        Assert.assertEquals(campaign.id,g.activeCampaign.value)
    }
    @Test fun copyDialogsExplainErrorsAndRestoreAsDraft() {
        var generated:File?=null
        compose.runOnUiThread { compose.activity.setContent { CampaignTheme { ExportCopyDialog(g,campaign,onDismiss={},onGenerated={generated=it}) } } }
        click("Guardar copia")
        compose.onNodeWithText("Usa una contraseña de al menos 10 caracteres.").assertExists();capture("07a-export-validation")
        fun secret(index:Int,value:String,replace:Boolean=false) {
            val field=compose.onAllNodes(hasSetTextAction())[index]
            runCatching{field.performScrollTo()}
            if(replace)field.performTextReplacement(value) else field.performTextInput(value)
        }
        secret(0,"ux-respaldo-2026");secret(1,"ux-otra-clave-2026")
        hideKeyboard();click("Guardar copia")
        compose.onNodeWithText("Las contraseñas no coinciden.").assertExists()
        secret(1,"ux-respaldo-2026",true)
        hideKeyboard();capture("07b-export-copy")
        // A keyboard/window transition can move the dialog between pointer down/up.
        // Invoke the native accessibility action so this assertion tests persistence.
        compose.onNodeWithText("Guardar copia").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        try { compose.waitUntil(15000){generated!=null} }
        catch(e:Throwable){capture("failure-export");compose.onRoot().printToLog("UX_EXPORT_STATE");throw e}
        createdFiles+=generated!!
        val uri=FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".files",generated!!)
        var restored:Campaign?=null
        compose.runOnUiThread { compose.activity.setContent { CampaignTheme { RestoreCopyDialog(g,uri,onDismiss={},onChangeFile={},onRestored={restored=it}) } } }
        capture("07c-restore-copy")
        secret(0,"clave-incorrecta-2026")
        hideKeyboard();click("Restaurar")
        compose.waitUntil(15000){compose.onAllNodesWithText("Contraseña incorrecta o copia dañada.").fetchSemanticsNodes().isNotEmpty()}
        capture("07d-restore-error")
        secret(0,"ux-respaldo-2026",true)
        hideKeyboard();click("Restaurar");compose.waitUntil(15000){restored!=null}
        Assert.assertNotEquals(campaign.id,restored!!.id);Assert.assertEquals("DRAFT",restored!!.mode)
        Assert.assertTrue(runBlocking{g.dao.tasks(restored!!.id).all{it.status=="DRAFT"}})
    }
}
