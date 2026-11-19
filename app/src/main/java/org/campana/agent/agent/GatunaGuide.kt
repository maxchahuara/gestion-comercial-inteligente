package org.campana.agent.agent

import org.campana.agent.data.Campaign
import org.campana.agent.data.normalized
import java.net.URI

/** A demo guide limited to the existing fictional campaign. */
internal object GatunaGuide {
    enum class Action { SUBSCRIBE, UNSUBSCRIBE, HANDOFF }
    data class Reply(val text: String, val action: Action? = null)

    fun applies(campaign: Campaign): Boolean = normalized(campaign.name) in setOf(
        "partido gatuno del peru", "partido gatuno del peru · prueba", "partido gatuno del peru - prueba"
    )

    private val groups = listOf(
        "🗳️ *Consultas de la prueba*" to listOf(GatunaTopic.MESA, GatunaTopic.TRANSIT, GatunaTopic.VOTE),
        "📋 *Propuestas y candidaturas*" to listOf(GatunaTopic.PLANS, GatunaTopic.NATIONAL, GatunaTopic.LIMA, GatunaTopic.REGIONS, GatunaTopic.CANDIDATES),
        "💬 *Recursos y ayuda*" to listOf(GatunaTopic.VIDEOS, GatunaTopic.RULES, GatunaTopic.MANUAL, GatunaTopic.TEAM)
    )

    private val menu = buildString {
        append("🐾 *Conexión Gatuna*\n")
        append("¡Hola! Soy el asistente del Partido Gatuno del Perú.\n")
        append("_Campaña ficticia de prueba_\n")
        append("8️⃣ *Marca el 8* · ¡Más ronroneos, menos rollos!\n")
        for ((heading, topics) in groups) {
            append("\n").append(heading)
            for (topic in topics) append("\n*").append(topic.number).append("* · ").append(topic.title)
            append("\n")
        }
        append("\n👉 Escribe el *número* de una opción o hazme una pregunta sobre la campaña.")
    }

    // Values come from the owner's approved information field.
    internal fun field(c: Campaign, label: String): String = c.knowledge.lineSequence().mapNotNull { line ->
        val split = line.indexOf(':')
        if (split > 0 && normalized(line.substring(0, split)) == normalized(label)) line.substring(split + 1).trim().take(900)
        else null
    }.singleOrNull().orEmpty()

    private fun url(value: String, hosts: Set<String>): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme == "https" && uri.host?.lowercase() in hosts &&
                uri.userInfo == null && uri.port == -1 && !uri.path.isNullOrBlank() && uri.path != "/"
        }
    }

    internal fun openingConfirmed(c: Campaign) = normalized(field(c, "Mesa de transeúntes")) == "abierta"

    internal fun openingText(c: Campaign): String = buildString {
        append("🔔 *Aviso gatuno de prueba*\n\nEl equipo confirmó la apertura simulada de la mesa de transeúntes.")
        field(c, "Fecha de apertura").takeIf { it.isNotEmpty() && !it.contains("://") }?.let { append("\n📅 ").append(it) }
        append("\n\nPara dejar de recibir este aviso, escribe *BAJA*.")
    }

    fun route(c: Campaign, incoming: String, posts: Map<GatunaTopic, String> = emptyMap()): Reply? {
        if (!applies(c)) return null
        val command = normalized(incoming).trim('¿', '?', '¡', '!', '.', ' ')
        if (command in setOf("hola", "hola gatunos", "menu", "0", "volver", "volver al menu", "buenos dias", "buenas tardes", "buenas noches"))
            return Reply(menu)
        if (command in setOf("que numero debo marcar", "que numero", "cual es el numero", "por quien voto", "numero de la campana"))
            return Reply("8️⃣ *Número de la demostración*\n\n🐾 Partido Gatuno del Perú · *Marca el 8*.\n¡Más ronroneos, menos rollos!\n\n↩️ Escribe *MENÚ* para volver.")
        if (command in setOf("aviso", "suscribirme", "si quiero recibir el aviso", "avisame cuando abra la inscripcion"))
            return Reply("🔔 *Aviso activado*\n\nCuando vuelvas a escribir, podré contarte si la apertura de prueba ya está confirmada.\n\nPara cancelar, escribe *BAJA*.", Action.SUBSCRIBE)
        if (command in setOf("baja", "cancelar aviso", "dejar de recibir avisos"))
            return Reply("✅ *Aviso cancelado*\n\nSi quieres volver a activarlo, escribe *AVISO*.", Action.UNSUBSCRIBE)

        if (command.startsWith("mesa gato-")) {
            val result = if (command == "mesa gato-08") "✅ *GATO-08* figura en la mesa ficticia 8."
            else "🔎 Ese código no figura en el padrón de prueba. Puedes ensayar con *MESA GATO-08*."
            return card(GatunaTopic.MESA, result, posts)
        }
        if (command.startsWith("candidato ") && command != "candidato de lima") {
            val name = command.removePrefix("candidato ").trim()
            val match = candidates(c).split(';').singleOrNull { normalized(it.substringBefore('·')) == name }?.trim()
            val result = if (match == null) "Ese nombre no figura en la lista de prueba. Prueba con *CANDIDATO LUNA BIGOTES*."
            else "✅ $match\n_Candidatura ficticia_"
            return card(GatunaTopic.CANDIDATES, result, posts)
        }

        val topic = when (command) {
            "miembro de mesa", "miembros de mesa", "soy miembro de mesa", "verifica si eres miembro de mesa", "como se si soy miembro de mesa" -> GatunaTopic.MESA
            "mesa de transeuntes", "transeuntes" -> GatunaTopic.TRANSIT
            "planes", "planes y propuestas", "propuestas", "que proponen" -> GatunaTopic.PLANS
            "nacional", "plan nacional" -> GatunaTopic.NATIONAL
            "lima", "plan lima", "plan para lima" -> GatunaTopic.LIMA
            "provincias", "plan provincias", "plan para provincias" -> GatunaTopic.REGIONS
            "candidaturas", "candidatos", "buscar candidato", "buscar un candidato", "candidato de lima" -> GatunaTopic.CANDIDATES
            "videos", "preguntas frecuentes", "videos y preguntas frecuentes", "faq" -> GatunaTopic.VIDEOS
            "reglamento", "reglamento electoral", "reglamento de la prueba", "reglamento de la demostracion" -> GatunaTopic.RULES
            "voto presencial", "participacion presencial", "por que es importante el voto presencial" -> GatunaTopic.VOTE
            "manual", "fuentes", "manual y fuentes", "enlace", "publicacion", "me pasas el enlace de la publicacion" -> GatunaTopic.MANUAL
            "humano", "equipo", "hablar con el equipo", "hablar con una persona" -> GatunaTopic.TEAM
            else -> GatunaTopic.entries.firstOrNull { it.number == command.toIntOrNull() }
        } ?: return null

        val body = when (topic) {
            GatunaTopic.MESA -> "Consulta el padrón ficticio con un código de prueba.\n\n👉 Escribe *MESA GATO-08*.\n🔒 Evita compartir tu DNI."
            GatunaTopic.TRANSIT -> {
                val status = if (openingConfirmed(c)) "🟢 *Apertura simulada confirmada*"
                else "🟡 *Apertura por confirmar*"
                "$status\n\nRevisa cómo funcionaría la mesa si estuvieras de viaje.\n\n🔔 Escribe *AVISO* para seguir la apertura en este chat."
            }
            GatunaTopic.PLANS -> "Elige la propuesta que quieras explorar.\n\n🇵🇪 *4* · Nacional\n🏙️ *5* · Lima\n⛰️ *6* · Provincias\n🔎 *7* · Candidaturas"
            GatunaTopic.NATIONAL -> plan(c, "Nacional", "Red de hogares temporales y apoyo para la adopción responsable.")
            GatunaTopic.LIMA -> plan(c, "Lima", "Jornadas barriales de adopción y educación sobre el cuidado de mascotas.")
            GatunaTopic.REGIONS -> plan(c, "Provincias", "Coordinación entre refugios locales y campañas de cuidado animal.")
            GatunaTopic.CANDIDATES -> candidates(c).split(';').joinToString("\n") { "• " + it.trim() } +
                "\n\n👉 Prueba *CANDIDATO LUNA BIGOTES* para ver su ficha."
            GatunaTopic.VIDEOS -> {
                val video = url(field(c, "Video"), setOf("instagram.com", "www.instagram.com", "tiktok.com", "www.tiktok.com", "youtube.com", "www.youtube.com", "youtu.be"))
                (video?.let { "▶️ *Ver video*\n$it" } ?: "El equipo aún no ha publicado un video para este tema.") +
                    "\n\n*Preguntas rápidas*\n• Número de la prueba: *8*\n• Propuestas: escribe *3*\n• Atención humana: escribe *12*"
            }
            GatunaTopic.RULES -> "Las mesas, los planes y el voto forman parte de una simulación. Usa este chat para consultar la demostración. Para trámites reales, consulta el canal oficial correspondiente."
            GatunaTopic.VOTE -> "La guía recorre la jornada presencial, desde ubicar la mesa hasta entender cómo se participa. 🐱 *Marca el 8* es parte de la simulación."
            GatunaTopic.MANUAL -> {
                val manual = url(field(c, "Manual"), setOf("facebook.com", "www.facebook.com", "instagram.com", "www.instagram.com", "drive.google.com", "docs.google.com"))
                listOfNotNull(
                    "*Comandos rápidos*\n• Padrón: *MESA GATO-08*\n• Candidaturas: *CANDIDATO LUNA BIGOTES*\n• Avisos: *AVISO* o *BAJA*",
                    manual?.let { "📎 *Abrir el manual*\n$it" }
                ).joinToString("\n\n")
            }
            GatunaTopic.TEAM -> "✅ Registré tu solicitud para el equipo. Cuéntame en una frase qué necesitas y quedará en este chat."
        }
        return card(topic, body, posts, if (topic == GatunaTopic.TEAM) Action.HANDOFF else null)
    }

    private fun card(topic: GatunaTopic, body: String, posts: Map<GatunaTopic, String>, action: Action? = null): Reply =
        Reply(buildString {
            append("${topic.icon} *${topic.title}*\n\n").append(body)
            posts[topic]?.let { append("\n\n🖼️ *Ver publicación de este tema*\n").append(it) }
            append("\n\n↩️ Escribe *MENÚ* para volver.")
        }, action)

    private fun plan(c: Campaign, scope: String, demo: String) =
        (field(c, "Plan $scope").takeUnless { it.contains("://") }.orEmpty().ifBlank { demo }) +
            "\n\n🐾 _Propuesta de la campaña ficticia_"

    private fun candidates(c: Campaign) = field(c, "Candidatos").takeUnless { it.contains("://") }.orEmpty()
        .ifBlank { "Michi Cobalto · Nacional; Luna Bigotes · Lima; Copito del Valle · Provincias" }
}
