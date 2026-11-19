package org.campana.agent

import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.campana.agent.agent.ScreenRules
import org.campana.agent.data.*
import org.campana.agent.phone.PhoneAccessibilityService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SocialSelfPhoneTest {
    @Test fun instagramMessageInPreviouslyVerifiedOwnConversation()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires an explicitly verified own conversation.",args.getString("instagramSelf")=="true")
        val recipient=args.getString("recipient") ?: error("Falta el destino propio verificado.")
        require(recipient.isNotBlank())
        val context=ApplicationProvider.getApplicationContext<Context>()
        val g=(context.applicationContext as CampaignApp).graph;g.ready.await()
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val component=context.packageName+"/org.campana.agent.phone.PhoneAccessibilityService"
        val before=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        require(component in before.split(":") && before.matches(Regex("[A-Za-z0-9_.$:/]*")))
        fun shell(command:String) { automation.executeShellCommand(command).use { p->java.io.FileInputStream(p.fileDescriptor).use { it.readBytes() } } }
        val remaining=before.split(":").filter { it!=component }.joinToString(":")
        shell(if(remaining.isEmpty())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services "+remaining)
        delay(1200);shell("settings put secure enabled_accessibility_services "+before)
        shell("settings put secure accessibility_enabled 1")
        val deadline=SystemClock.elapsedRealtime()+8000
        while(PhoneAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline)delay(100)
        val service=checkNotNull(PhoneAccessibilityService.instance)
        val initial=withContext(Dispatchers.Main) { service.observe() }
        require(initial.packageName==Channel.INSTAGRAM.packageName &&
            ScreenRules.destinationMatches(initial.nodes.map { it.resource to it.text },initial.packageName,recipient))
        val previousUi=g.prefs.getBoolean("ui_enabled",false);val previousPause=g.paused.value
        g.prefs.edit().putBoolean("ui_enabled",true).commit();g.pause(false)
        val c=Campaign(name="QA · Instagram propio",objective="Comprobar un único texto en mi conversación propia.",channels="INSTAGRAM",mode="AUTO",recipients=recipient,dailyLimit=1,quietStart=0,quietEnd=0)
        val text="Prueba de Campaña 0.1.0: mensaje de Instagram preparado y enviado desde el teléfono."
        val existing=initial.nodes.first { it.resource=="com.instagram.android:id/row_thread_composer_edittext" }
        require(existing.text==text || existing.text.isBlank() || existing.showingHint)
        val instruction=if(existing.text==text)"El borrador ya contiene exactamente el texto autorizado. No lo reescribas: comprueba el destino, pulsa Enviar una sola vez y observa el resultado. Texto autorizado: " else "Observa, escribe exactamente este texto y pulsa Enviar una sola vez: "
        val task=AgentTask(campaignId=c.id,channel="INSTAGRAM",recipient=recipient,instruction="La conversación propia ya está seleccionada. "+instruction+text+" No cambies de conversación ni edites perfiles. Observa el resultado y termina.")
        g.dao.save(c)
        try {
            g.dao.insert(task);g.engine.runTask(task.id)
            val result=g.dao.task(task.id)!!
            assertEquals(result.detail,"SUBMITTED",result.status)
            val after=withContext(Dispatchers.Main) { service.observe() }
            assertTrue(after.nodes.any { !it.editable && (it.text==text || it.description.contains(text)) })
            assertEquals(1,g.dao.allReceipts(c.id).count { it.action=="external_submit" })
        } finally {
            g.prefs.edit().putBoolean("ui_enabled",previousUi).commit();g.pause(previousPause || g.paused.value)
            g.dao.save(c.copy(mode="DRAFT"))
        }
    }
}
