package org.campana.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun normalized(value: String): String = Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

fun sameContact(left:String,right:String):Boolean {
    if(left.isBlank() || right.isBlank())return false
    if(normalized(left)==normalized(right))return true
    fun phone(s:String):String? = s.replace(Regex("\\p{Cf}"),"").trim().takeIf{it.matches(Regex("\\+?[0-9 ()-]{8,24}"))}?.filter{it.isDigit()}?.takeIf{it.length in 8..15}
    val a=phone(left);val b=phone(right)
    return a!=null && b!=null && a==b
}

fun campaignModeLabel(mode:String):String=when(mode) {
    "AUTO"->"Automática según tus reglas"
    "REACTIVE"->"Solo responder mensajes entrantes"
    else->"Modo borradores"
}

enum class Channel(val title: String, val packageName: String) {
    WHATSAPP("WhatsApp", "com.whatsapp"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    MESSENGER("Messenger", "com.facebook.orca"),
    FACEBOOK("Facebook", "com.facebook.katana");
    companion object { fun forPackage(pkg: String) = entries.firstOrNull { it.packageName == pkg } }
}

@Serializable @Entity
data class Campaign(
    @PrimaryKey val id: String = newId(),
    val name: String = "",
    val objective: String = "",
    val offer: String = "",
    val audience: String = "",
    val tone: String = "Cercano, claro y profesional",
    val knowledge: String = "",
    val channels: String = "WHATSAPP",
    val mode: String = "DRAFT",
    val recipients: String = "",
    val accountIdentity: String = "",
    val dailyLimit: Int = 20,
    val quietStart: Int = 21,
    val quietEnd: Int = 8,
    val autoReply: Boolean = false,
    val allowPublishing: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun channelList() = channels.split(",").mapNotNull { runCatching { Channel.valueOf(it) }.getOrNull() }
    fun isDedicatedWhatsApp()=mode=="REACTIVE" && autoReply && channels=="WHATSAPP"
    fun isPublicInbox()=mode=="REACTIVE" && autoReply
    fun allowsRecipient(recipient: String) = recipient.isNotBlank() && recipient!="*" &&
        recipients.lines().filter{it.trim()!="*"}.any { sameContact(it,recipient) }
    fun allowsIncomingRecipient(recipient:String)=allowsRecipient(recipient) ||
        (isPublicInbox() && recipient.isNotBlank())
}

@Serializable @Entity(indices = [Index(value = ["dedupeKey"], unique = true)])
data class AgentTask(
    @PrimaryKey val id: String = newId(),
    val campaignId: String,
    val channel: String,
    val kind: String = "REPLY",
    val instruction: String,
    val recipient: String = "",
    val assetId: String = "",
    val dueAt: Long = System.currentTimeMillis(),
    val status: String = "QUEUED",
    val detail: String = "",
    val dedupeKey: String? = null,
    val notificationKey: String = "",
    val notificationFingerprint: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable @Entity
data class ChatMessage(
    @PrimaryKey val id: String = newId(),
    val campaignId: String,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable @Entity
data class Asset(
    @PrimaryKey val id: String = newId(), val campaignId: String,
    val name: String, val mime: String, val fileName: String,
    val extractedText: String = "", val createdAt: Long = System.currentTimeMillis()
)

@Serializable @Entity
data class Lead(
    @PrimaryKey val id: String = newId(), val campaignId: String,
    val name: String, val channel: String, val contact: String,
    val notes: String, val stage: String = "Nuevo"
)

@Serializable @Entity
data class Receipt(
    @PrimaryKey val id: String = newId(), val campaignId: String,
    val taskId: String = "", val action: String, val status: String, val detail: String,
    val createdAt: Long = System.currentTimeMillis()
)
