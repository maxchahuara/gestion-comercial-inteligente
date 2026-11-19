package org.campana.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.campana.agent.data.Campaign
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivePhoneTest {
    @Test fun inferenceAndToolCycleFromPhone()=runBlocking {
        assumeTrue("Live tests require -e live true and an authorized ChatGPT account.",
            InstrumentationRegistry.getArguments().getString("live")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val g=(context.applicationContext as CampaignApp).graph
        g.ready.await()
        assertNotNull("Connect ChatGPT from the phone first.",g.auth.vault.active())
        g.refreshModels()
        val campaign=Campaign(name="QA · ciclo real del agente",objective="Comprobar herramientas sin enviar contenido.",
            offer="Prueba técnica local",knowledge="Solo es una prueba del teléfono. No hay una oferta comercial.",mode="DRAFT")
        g.dao.save(campaign)
        try {
            g.engine.chat(campaign.id,"Responde exactamente PRUEBA_LOCAL_OK. No uses herramientas.")
            assertTrue(g.dao.history(campaign.id).any { it.role=="assistant" && it.text.contains("PRUEBA_LOCAL_OK") })
            g.engine.chat(campaign.id,"Ejecuta estas dos herramientas ahora: crea una imagen con título Prueba local y cuerpo Imagen técnica del agente; después crea una tarea tipo MESSAGE, canal WHATSAPP, instrucción Prueba sin envío, destinatario Contacto QA, sin adjunto, delay_minutes 60. Esta campaña está en borradores; no abras apps ni envíes nada. Confirma los IDs obtenidos.")
            assertEquals("El agente debe crear una imagen mediante su herramienta.",1,g.dao.assets(campaign.id).size)
            val tasks=g.dao.tasks(campaign.id)
            assertEquals("El agente debe guardar la tarea.",1,tasks.size)
            assertEquals("DRAFT",tasks.single().status)
        } finally {
            g.dao.assets(campaign.id).forEach { g.assets.file(it).delete() }
            g.dao.deleteCampaign(campaign.id)
        }
    }
}
