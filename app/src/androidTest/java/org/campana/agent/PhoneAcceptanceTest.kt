package org.campana.agent

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.content.ContextWrapper
import android.app.UiAutomation
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.campana.agent.data.*
import org.campana.agent.phone.*
import org.campana.agent.auth.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAcceptanceTest {
    @Test fun facebookPostShareSelectsOnlyTheInstalledNativeFeedComposer() {
        org.junit.Assume.assumeTrue(org.campana.agent.phone.SocialApps.installed(context,Channel.FACEBOOK))
        val intent=android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png").setPackage(Channel.FACEBOOK.packageName)
        org.campana.agent.phone.SocialApps.targetShare(context,Channel.FACEBOOK,"POST",intent)
        assertEquals("com.facebook.katana",intent.component!!.packageName)
        assertEquals("com.facebook.composer.shareintent.ImplicitShareIntentHandlerDefaultAlias",intent.component!!.className)
        val whatsapp=android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png").setPackage(Channel.WHATSAPP.packageName)
        org.campana.agent.phone.SocialApps.targetShare(context,Channel.WHATSAPP,"ATTACHMENT",whatsapp)
        assertNull(whatsapp.component)
    }
    @Test fun foregroundAutomationServiceIsPrivateAndTyped() {
        val name=android.content.ComponentName(context,"androidx.work.impl.foreground.SystemForegroundService")
        val info=context.packageManager.getServiceInfo(name,0)
        assertFalse(info.exported)
        if(android.os.Build.VERSION.SDK_INT>=34)assertTrue(info.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission("android.permission.FOREGROUND_SERVICE"))
    }
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun ownerCannotRetryOrCancelAnUncertainSubmission()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,CampaignDatabase::class.java).build()
        try {
            val dao=db.dao();val campaign=Campaign(name="QA transiciones",objective="Comprobar revisión sin efectos externos");dao.save(campaign)
            val task=AgentTask(campaignId=campaign.id,channel="WHATSAPP",instruction="Solo prueba local",status="DRAFT");dao.insert(task)
            assertEquals(1,dao.queueReviewed(task.id,System.currentTimeMillis()))
            assertEquals(0,dao.queueReviewed(task.id,System.currentTimeMillis()))
            assertEquals(1,dao.claim(task.id,System.currentTimeMillis()))
            assertEquals(0,dao.cancelPending(task.id,System.currentTimeMillis()))
            dao.taskState(task.id,"UNCERTAIN","Resultado no confirmado")
            assertEquals(0,dao.queueReviewed(task.id,System.currentTimeMillis()))
            assertEquals(0,dao.cancelPending(task.id,System.currentTimeMillis()))
            assertEquals("UNCERTAIN",dao.task(task.id)!!.status)
        } finally {db.close()}
    }
    @Test fun rsaVerificationWorksOnAndroid() { assertTrue(LocalDiagnostics.verifyRsa(context)) }
    @Test fun databaseDeduplicatesClaimsAndRecoversUncertain()= runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,CampaignDatabase::class.java).build()
        try {
            val dao=db.dao();val c=Campaign(name="Prueba de base",objective="Verificar aislamiento");dao.save(c)
            val other=Campaign(name="Otra",objective="Separada");dao.save(other)
            val task=AgentTask(campaignId=c.id,channel="WHATSAPP",instruction="Prueba local",dedupeKey="message-1")
            assertTrue(dao.insert(task)!=-1L)
            assertEquals(-1L,dao.insert(task.copy(id=newId())))
            assertEquals(1,dao.claim(task.id,System.currentTimeMillis()))
            assertEquals(0,dao.claim(task.id,System.currentTimeMillis()))
            dao.recoverInterrupted();assertEquals("UNCERTAIN",dao.task(task.id)!!.status)
            assertEquals(0,dao.waitBeforeStarting(task.id,"No iniciar",System.currentTimeMillis()))
            assertEquals("UNCERTAIN",dao.task(task.id)!!.status)
            dao.deleteCampaign(c.id);assertNull(dao.campaign(c.id));assertNotNull(dao.campaign(other.id))
        } finally { db.close() }
    }
    @Test fun encryptedBackupRoundTripAndWrongPassword()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,CampaignDatabase::class.java).build()
        val files=mutableListOf<File>()
        try {
            val dao=db.dao();val c=Campaign(name="QA copia",objective="Comprobar recuperación",mode="AUTO",autoReply=true,allowPublishing=true)
            dao.save(c)
            val repository=AssetRepository(context,dao)
            val asset=repository.createImage(c.id,"Prueba local","Esta pieza no se publica en ninguna red.")
            files+=repository.file(asset)
            dao.insert(AgentTask(campaignId=c.id,channel="WHATSAPP",instruction="Solo prueba",assetId=asset.id,
                notificationKey="stale",notificationFingerprint="stale",dedupeKey="old"))
            val backup=repository.backup(c.id,"clave-de-prueba-local".toCharArray());files+=backup
            assertFalse(String(backup.readBytes(),Charsets.ISO_8859_1).contains("QA copia"))
            val uri=FileProvider.getUriForFile(context,context.packageName+".files",backup)
            try { repository.restore(uri,"contraseña-incorrecta".toCharArray());fail("Debe rechazar una contraseña incorrecta") } catch(_:IllegalStateException) {}
            val restored=repository.restore(uri,"clave-de-prueba-local".toCharArray())
            assertNotEquals(c.id,restored.id);assertEquals("DRAFT",restored.mode)
            assertFalse(restored.autoReply);assertFalse(restored.allowPublishing)
            val restoredAssets=dao.assets(restored.id);assertEquals(1,restoredAssets.size);files+=repository.file(restoredAssets.single())
            val tasks=dao.tasks(restored.id);assertEquals("DRAFT",tasks.single().status)
            assertEquals("",tasks.single().notificationKey);assertNull(tasks.single().dedupeKey)
        } finally { files.forEach { it.delete() };db.close() }
    }
    @Test fun vaultEncryptsAndKeepsAccountsSeparate() {
        val folder=File(context.noBackupFilesDir,"vault-qa-"+newId()).apply { mkdirs() }
        val isolated=object:ContextWrapper(context) { override fun getNoBackupFilesDir():File=folder }
        try {
            val vault=SessionVault(isolated)
            val a=Session("client-a","account-a","a@example.invalid","qa-access-a","qa-refresh-a","qa-id-a",123,"openid")
            val b=a.copy(clientId="client-b",subject="account-b",accessToken="qa-access-b")
            vault.put(a);vault.put(b);assertEquals("account-b",vault.active()!!.subject)
            vault.select("client-a");assertEquals("qa-access-a",vault.active()!!.accessToken)
            assertEquals(2,vault.read().sessions.size)
            assertFalse(String(File(folder,"identity.bin").readBytes(),Charsets.ISO_8859_1).contains("qa-access"))
        } finally { folder.listFiles()?.forEach { it.delete() };folder.delete() }
    }
    @Test fun screenCanReadWriteAndClickInLocalLab() {
        val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Instrumentation restarts the target process; rebind only this already-authorized test service.
        val component=context.packageName+"/org.campana.agent.phone.PhoneAccessibilityService"
        val before=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertTrue("Autoriza el servicio desde Ajustes antes de esta prueba.",component in before.split(":"))
        require(before.matches(Regex("[A-Za-z0-9_.$:/]*")))
        fun shell(command:String) { automation.executeShellCommand(command).use { descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } } }
        val remaining=before.split(":").filter { it!=component }.joinToString(":")
        // UiAutomation uses Runtime.exec, not a shell; quotes would become part of the component value.
        shell(if(remaining.isEmpty())"settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services "+remaining)
        SystemClock.sleep(1200)
        shell("settings put secure enabled_accessibility_services "+before)
        shell("settings put secure accessibility_enabled 1")
        val deadline=SystemClock.elapsedRealtime()+8_000
        while(PhoneAccessibilityService.instance==null && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
        assertNotNull("Habilita el servicio de Accesibilidad antes de ejecutar esta prueba.",PhoneAccessibilityService.instance)
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        try {
        ActivityScenario.launch<SandboxActivity>(Intent(context,SandboxActivity::class.java)).use { scenario ->
            SystemClock.sleep(800)
            var control:PhoneAccessibilityService.ControlSession?=null
            scenario.onActivity { control=PhoneAccessibilityService.instance!!.beginControl(localOnly=true) }
            SystemClock.sleep(200)
            try {
            var snapshot:ScreenState?=null
            scenario.onActivity { snapshot=PhoneAccessibilityService.instance!!.observe() }
            val first=snapshot!!
            val input=first.nodes.first { it.resource.endsWith("/sandbox_input") }
            scenario.onActivity { assertTrue(PhoneAccessibilityService.instance!!.act(first.id,input.id,"write","Prueba autónoma local")) }
            scenario.onActivity { snapshot=PhoneAccessibilityService.instance!!.observe() }
            val second=snapshot!!
            val button=second.nodes.first { it.resource.endsWith("/sandbox_send") }
            scenario.onActivity { assertTrue(PhoneAccessibilityService.instance!!.act(second.id,button.id,"click","")) }
            val updateDeadline=SystemClock.elapsedRealtime()+3000
            do {
                SystemClock.sleep(100)
                scenario.onActivity { snapshot=PhoneAccessibilityService.instance!!.observe() }
            } while(snapshot!!.nodes.none { it.text.contains("Recibido localmente: Prueba autónoma local") } && SystemClock.elapsedRealtime()<updateDeadline)
            assertTrue(snapshot!!.nodes.any { it.text.contains("Recibido localmente: Prueba autónoma local") })
            scenario.onActivity {
                assertThrows(IllegalStateException::class.java) { PhoneAccessibilityService.instance!!.act(first.id,input.id,"write","No debe ejecutarse") }
            }
            } finally {scenario.onActivity {control?.close()}}
        }
        } finally { automation.setRotation(UiAutomation.ROTATION_UNFREEZE) }
    }
}
