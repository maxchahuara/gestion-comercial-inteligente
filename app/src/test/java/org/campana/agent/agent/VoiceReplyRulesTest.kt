package org.campana.agent.agent
import org.campana.agent.audio.VoiceReplyRules
import org.junit.Assert.*
import org.junit.Test

class VoiceReplyRulesTest {
    @Test fun anAudioTransportQuestionGetsAnAcknowledgement() {
        assertNotNull(VoiceReplyRules.confirmation("Si logras entender este audio, ¿se escucha?"))
        assertNotNull(VoiceReplyRules.confirmation("¿Me escuchas?"))
    }
    @Test fun aCampaignQuestionStillGoesToTheCampaignGenerator() {
        assertNull(VoiceReplyRules.confirmation("¿Me escuchas? ¿Qué número debo marcar?"))
        assertNull(VoiceReplyRules.confirmation("Dame más información sobre la campaña"))
    }
    @Test fun unrelatedContentIsNotConvertedToAnAudioAcknowledgement() {
        assertNull(VoiceReplyRules.confirmation("Dime una receta de cocina"))
        assertNull(VoiceReplyRules.confirmation("hola"))
        assertNull(VoiceReplyRules.confirmation("¿Me escuchas? "+"cocina ".repeat(40)))
    }
}
