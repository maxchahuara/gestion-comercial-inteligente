package org.campana.agent.agent

import androidx.room.withTransaction
import org.campana.agent.AppGraph
import org.campana.agent.data.AgentTask
import org.campana.agent.data.Campaign
import org.campana.agent.data.Channel
import org.campana.agent.data.Receipt
import org.campana.agent.data.normalized
import java.security.MessageDigest

/** Used only after the engine has verified the source conversation. */
internal object GatunaSupport {
    private const val SUBSCRIPTION = "gatuna_subscription"
    private const val OPENING = "gatuna_opening"
    private const val HANDOFF = "gatuna_handoff"
    suspend fun prepareTopicPosts(g: AppGraph, c: Campaign): Int {
        if (!GatunaGuide.applies(c)) return 0
        val page = c.accountIdentity.ifBlank {
            g.dao.tasks(c.id).firstOrNull {
                it.kind == "POST" && it.channel == Channel.FACEBOOK.name && it.status == "SUBMITTED"
            }?.recipient.orEmpty()
        }
        if (page.isBlank()) return 0
        return g.db.withTransaction {
            val existingAssets = g.dao.assets(c.id).associateBy { it.name }
            var created = 0
            for (topic in GatunaTopic.entries) {
                val key = topic.draftKey(c.id)
                if (g.dao.taskForDedupe(key) != null) continue
                val asset = existingAssets["${topic.assetName}.jpg"]?.takeIf { g.assets.file(it).isFile }
                    ?: g.assets.saveGeneratedImage(c.id, g.context.assets.open("gatuna/${topic.slug}.jpg").use { it.readBytes() }, topic.assetName)
                if (g.dao.insert(AgentTask(campaignId = c.id, channel = Channel.FACEBOOK.name, kind = "POST",
                        instruction = topic.caption, recipient = page, assetId = asset.id, status = "DRAFT", dedupeKey = key)) != -1L) {
                    created++
                }
            }
            val old = g.dao.taskForDedupe("gatuna-imagegen-sample:${c.id}")
            if (old != null && !g.dao.hasReceipt(old.id, "external_submit") && g.dao.cancelPending(old.id, System.currentTimeMillis()) == 1) {
                g.dao.taskState(old.id, "CANCELLED", "Sustituida por las publicaciones temáticas de Conexión Gatuna.")
            }
            created
        }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun conversation(c: Campaign, task: AgentTask): String {
        require(task.kind == "REPLY" && c.id == task.campaignId && task.notificationKey.isNotBlank())
        return hash(listOf(task.channel, task.notificationKey, normalized(task.recipient), normalized(c.accountIdentity)).joinToString("\u0000"))
    }

    private fun opening(c: Campaign, key: String) = hash(key + "|" + GatunaGuide.field(c, "Fecha de apertura"))

    suspend fun draft(g: AppGraph, c: Campaign, task: AgentTask, incoming: String, posts: Map<GatunaTopic, String>): String? {
        if (!GatunaGuide.applies(c)) return null
        val reply = GatunaGuide.route(c, incoming, posts)
        val key = conversation(c, task)
        return g.db.withTransaction {
            check(g.dao.campaign(c.id)?.updatedAt == c.updatedAt && !g.paused.value && g.incomingWake.value) {
                "La atención de campaña cambió."
            }
            val update = when (reply?.action) {
                GatunaGuide.Action.SUBSCRIBE -> SUBSCRIPTION to "ACTIVE"
                GatunaGuide.Action.UNSUBSCRIBE -> SUBSCRIPTION to "CANCELLED"
                GatunaGuide.Action.HANDOFF -> HANDOFF to "WAITING_OWNER"
                null -> null
            }
            if (update != null && !g.dao.hasReceipt(task.id, update.first)) {
                g.dao.insert(Receipt(campaignId = c.id, taskId = task.id, action = update.first, status = update.second, detail = key))
            }
            val optedIn = g.dao.latestGuideReceipt(c.id, SUBSCRIPTION, key)?.status == "ACTIVE"
            val pending = optedIn && GatunaGuide.openingConfirmed(c) &&
                g.dao.latestGuideReceipt(c.id, OPENING, opening(c, key)) == null
            if (pending && reply?.action != GatunaGuide.Action.HANDOFF) {
                listOfNotNull(reply?.text, GatunaGuide.openingText(c)).joinToString("\n\n")
            } else reply?.text
        }
    }

    suspend fun submitted(g: AppGraph, c: Campaign, task: AgentTask, text: String) {
        if (GatunaGuide.applies(c) && GatunaGuide.openingConfirmed(c) && text.contains(GatunaGuide.openingText(c))) {
            val key = opening(c, conversation(c, task))
            g.db.withTransaction {
                if (g.dao.latestGuideReceipt(c.id, OPENING, key) == null) {
                    g.dao.insert(Receipt(campaignId = c.id, taskId = task.id, action = OPENING, status = "SUBMITTED", detail = key))
                }
            }
        }
    }
}
