package org.campana.agent.agent

/** The numbering is shared by the WhatsApp menu and the campaign's Facebook drafts. */
internal enum class GatunaTopic(val number: Int, val slug: String, val icon: String, val title: String, val description: String) {
    MESA(1, "mesa", "🗳️", "Miembros de mesa",
        "¿Te toca ser miembro de mesa en el mundo gatuno? Escribe MESA GATO-08 a Conexión Gatuna y consulta el padrón de demostración. Usa el código de prueba y protege tus datos personales."),
    TRANSIT(2, "transeuntes", "🧳", "Mesa de transeúntes",
        "¿Estarías de viaje el día de la votación gatuna? Consulta cómo funciona la mesa de transeúntes en esta simulación. Escribe 2 y, si quieres seguir la apertura, envía AVISO."),
    PLANS(3, "planes", "📋", "Planes y propuestas",
        "Hogares temporales, adopción responsable y refugios coordinados. Conoce las propuestas de la demostración y elige qué plan explorar: Nacional, Lima o Provincias."),
    NATIONAL(4, "nacional", "🇵🇪", "Plan Nacional",
        "Imaginamos una red de hogares temporales en todo el Perú, con apoyo para quienes rescatan y preparan a un gato para su adopción. Escribe 4 para consultar el Plan Nacional de prueba."),
    LIMA(5, "lima", "🏙️", "Plan Lima",
        "Jornadas de adopción cerca de casa y orientación práctica sobre el cuidado responsable. Escribe 5 para consultar el Plan Lima de la demostración."),
    REGIONS(6, "provincias", "⛰️", "Plan Provincias",
        "Refugios locales que comparten recursos y experiencias entre regiones. Escribe 6 para conocer cómo funcionaría esa coordinación en la campaña ficticia."),
    CANDIDATES(7, "candidatura", "🔎", "Candidaturas",
        "Conoce quién participa en cada ámbito de la simulación. Escribe 7 para ver la lista o prueba CANDIDATO LUNA BIGOTES para consultar una ficha."),
    VIDEOS(8, "videos", "🎬", "Videos y preguntas frecuentes",
        "¿Qué número identifica la demostración? ¿Dónde están las propuestas? Escribe 8 para encontrar respuestas rápidas y los videos que apruebe el equipo."),
    RULES(9, "reglas", "📘", "Reglamento de la prueba",
        "Consulta qué puedes ensayar en Conexión Gatuna y qué gestiones corresponden a canales oficiales. En el chat, escribe 9."),
    VOTE(10, "voto", "🐾", "Participación presencial",
        "Desde ubicar la mesa hasta entender la dinámica de la jornada, la demostración recorre cómo se explicaría la participación presencial. Escribe 10 para ver la guía."),
    MANUAL(11, "manual", "📚", "Manual y fuentes",
        "Una guía rápida para consultar el padrón ficticio, buscar candidaturas y administrar tus avisos. Escribe 11 para abrir el manual y sus recursos."),
    TEAM(12, "equipo", "🙋", "Hablar con el equipo",
        "¿Tu consulta necesita atención humana? Escribe 12 para registrar tu solicitud y añade el contexto necesario en el mismo chat.");

    val assetName get() = "Gatuna · ${number.toString().padStart(2, '0')} · $title"
    val caption get() = "$icon $title\n\n$description\n\n🐾 Conexión Gatuna · Partido Gatuno del Perú\nCampaña ficticia de prueba."

    fun draftKey(campaignId: String) = "gatuna-topic-v1:$campaignId:$slug"
}
