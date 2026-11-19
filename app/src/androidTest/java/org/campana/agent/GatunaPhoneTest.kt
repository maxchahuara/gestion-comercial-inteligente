package org.campana.agent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.campana.agent.agent.CampaignImageGeneration
import org.campana.agent.agent.GatunaGuide
import org.campana.agent.agent.GatunaSupport
import org.campana.agent.agent.GatunaTopic
import org.campana.agent.data.Receipt
import org.campana.agent.data.AgentTask
import org.campana.agent.data.Campaign
import org.junit.Assert.*
import org.junit.Test

class GatunaPhoneTest {
    @Test fun menuConsentHandoffAndImageDraftRunOnPhone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".visualqa"))
        val g = (context.applicationContext as CampaignApp).graph
        g.ready.await()
        val c = Campaign(name = "Partido Gatuno del Perú · Prueba", channels = "WHATSAPP,FACEBOOK", accountIdentity = "Página de prueba")
        val campaign = c.copy(knowledge = "Mesa de transeúntes: ABIERTA\nFecha de apertura: 24 de noviembre (prueba)")
        g.pause(true)
        g.paused.value = false // Allow drafting only; the isolated app has no account or enabled services.
        try {
            g.dao.save(campaign)
            fun reply(message: String) = checkNotNull(GatunaGuide.route(campaign, message)).text
            assertTrue(reply("Hola").contains("*12* · Hablar con el equipo"))
            assertTrue(reply("Hola").contains("*Propuestas y candidaturas*"))
            for (item in 1..12) assertTrue(reply(item.toString()).contains("MENÚ"))
            assertTrue(reply("MESA GATO-08").contains("mesa ficticia 8"))
            assertTrue(reply("CANDIDATO LUNA BIGOTES").contains("Luna Bigotes · Lima"))
            assertNull(GatunaGuide.route(campaign, "Dame una receta", emptyMap()))
            assertNull(GatunaGuide.route(campaign.copy(name = "Otra campaña"), "Hola", emptyMap()))
            fun incoming(text: String, key: String = "fixture-chat-one") = AgentTask(
                campaignId = c.id, channel = "WHATSAPP", instruction = text, recipient = "Contacto de prueba",
                notificationKey = key, status = "DRAFT"
            )
            val subscribe = incoming("AVISO")
            assertFalse(GatunaSupport.draft(g, campaign, incoming("Hola"), "Hola", emptyMap())!!.contains("Aviso gatuno de prueba"))
            val opening = GatunaSupport.draft(g, campaign, subscribe, "AVISO", emptyMap())!!
            assertTrue(opening.contains("Aviso gatuno de prueba"))
            GatunaSupport.submitted(g, campaign, subscribe, opening)
            assertFalse(GatunaSupport.draft(g, campaign, incoming("2"), "2", emptyMap())!!.contains("Aviso gatuno de prueba"))
            val optOut = incoming("BAJA")
            GatunaSupport.draft(g, campaign, optOut, "BAJA", emptyMap())
            assertEquals("CANCELLED", g.dao.latestReceiptForTask(optOut.id, "gatuna_subscription")?.status)
            assertFalse(GatunaSupport.draft(g, campaign, incoming("2", "fixture-chat-two"), "2", emptyMap())!!.contains("Aviso gatuno de prueba"))
            val handoff = incoming("12")
            GatunaSupport.draft(g, campaign, handoff, "12", emptyMap())
            assertEquals("WAITING_OWNER", g.dao.latestReceiptForTask(handoff.id, "gatuna_handoff")?.status)

            val old = AgentTask(campaignId = c.id, channel = "FACEBOOK", kind = "POST",
                instruction = "Muestra anterior", recipient = campaign.accountIdentity, status = "WAITING_OWNER",
                dedupeKey = "gatuna-imagegen-sample:${c.id}")
            g.dao.insert(old)
            assertEquals(12, GatunaSupport.prepareTopicPosts(g, campaign))
            assertEquals(0, GatunaSupport.prepareTopicPosts(g, campaign))
            assertEquals("CANCELLED", g.dao.task(old.id)?.status)
            val posts = g.dao.tasks(c.id).filter { it.status == "DRAFT" && it.kind == "POST" }
            assertEquals(12, posts.size)
            assertEquals(12, posts.map { it.assetId }.distinct().size)
            for (topic in GatunaTopic.entries) {
                val post = posts.single { it.dedupeKey == topic.draftKey(c.id) }
                assertEquals(campaign.accountIdentity, post.recipient)
                assertEquals(topic.caption, post.instruction)
                val asset = checkNotNull(g.dao.asset(post.assetId))
                assertTrue(g.assets.file(asset).readBytes().contentEquals(context.assets.open("gatuna/${topic.slug}.jpg").use { it.readBytes() }))
                assertEquals("content", g.assets.uri(asset).scheme)
            }
            val mesa = posts.single { it.dedupeKey == GatunaTopic.MESA.draftKey(c.id) }
            val mesaUrl = "https://www.facebook.com/ExamplePage/posts/123456789"
            val legacyUrl = "https://www.facebook.com/ExamplePage/posts/987654321"
            fun link(parent: AgentTask, account: String, url: String) = Receipt(campaignId = c.id,
                taskId = parent.id, action = "post_link_verified", status = "OBSERVED",
                detail = """{"parent_task_id":"${parent.id}","account":"$account","url":"$url"}""")
            g.dao.insert(link(old, campaign.accountIdentity, legacyUrl))
            g.dao.insert(link(mesa, campaign.accountIdentity, mesaUrl))
            assertTrue(g.postLinks.gatunaLinks(campaign).isEmpty()) // A draft cannot provide a link.
            g.dao.taskState(mesa.id, "SUBMITTED", "Prueba local")
            g.dao.insert(link(mesa, "Página distinta", legacyUrl))
            val links = g.postLinks.gatunaLinks(campaign)
            assertEquals(mapOf(GatunaTopic.MESA to mesaUrl), links)
            assertTrue(GatunaGuide.route(campaign, "1", links)!!.text.contains(mesaUrl))
            assertFalse(GatunaGuide.route(campaign, "2", links)!!.text.contains(mesaUrl))
            assertFalse(GatunaGuide.route(campaign, "Hola", links)!!.text.contains(mesaUrl))
            assertEquals(0, g.dao.submissions(c.id, 0))
            val brief = CampaignImageGeneration.visualBrief("Gato en una plaza de Lima")
            assertTrue(brief.endsWith("Gato en una plaza de Lima"))
            try {
                g.codexImages.refreshAccount()
                assertTrue(g.codexImages.state.value.runtime.contains("Codex 0.166.0"))
                assertFalse(g.codexImages.state.value.signedIn)
            } finally { g.codexImages.stop() }

        } finally {
            g.pause(true)
            g.dao.assets(c.id).forEach { g.assets.file(it).delete() }
            g.dao.deleteCampaign(c.id)
        }
        Unit
    }
}
