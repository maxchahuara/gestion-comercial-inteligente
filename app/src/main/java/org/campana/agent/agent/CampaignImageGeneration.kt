package org.campana.agent.agent

import org.campana.agent.AppGraph
import org.campana.agent.data.Asset
import org.campana.agent.data.Campaign

internal object CampaignImageGeneration {
    fun visualBrief(prompt: String): String {
        require(prompt.trim().length in 1..2600) { "Describe la imagen en un máximo de 2600 caracteres." }
        return "Genera una pieza visual original cuadrada para una publicación. Usa únicamente el encargo visual de abajo. " +
            "Crea una imagen con composición cuidada, sujeto claro y legibilidad en móvil. El texto y los emojis de la " +
            "publicación irán en la descripción por separado. No añadas texto legible, marcas de agua ni sellos oficiales.\n\n" + prompt.trim()
    }

    suspend fun generate(g: AppGraph, campaign: Campaign, prompt: String): Asset {
        val image = g.codexImages.generate(visualBrief(prompt)) { g.status.value = it }
        return g.assets.saveGeneratedImage(campaign.id, image)
    }
}
