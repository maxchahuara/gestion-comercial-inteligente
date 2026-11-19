package org.campana.agent.audio

import org.campana.agent.data.normalized

/** A transport acknowledgement is based on completed transcription, never campaign facts. */
object VoiceReplyRules {
    fun confirmation(text:String):String? {
        val s=normalized(text).replace(Regex("[^a-z0-9 ]")," ").replace(Regex("\\s+")," ").trim()
        if(s.length>180 || Regex("campana|numero|informacion|propuesta|publicacion|enlace|que hacen|de que se trata").containsMatchIn(s))return null
        val check=Regex("me escuchas|se escucha|se oye|puedes (escuchar|oir|entender)|logras entender|entiendes (este|el) audio|entendiste (este|el) audio").containsMatchIn(s)
        if(!check)return null
        return "Sí, pude transcribir tu nota de voz. ¿Qué te gustaría saber sobre la campaña?"
    }
}
