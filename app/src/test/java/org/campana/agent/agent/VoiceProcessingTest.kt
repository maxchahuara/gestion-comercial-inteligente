package org.campana.agent.agent

import org.campana.agent.audio.*
import org.campana.agent.phone.VoiceMessageRules
import org.campana.agent.phone.IncomingMessage
import org.campana.agent.data.Channel
import org.junit.Assert.*
import org.junit.Test

class VoiceProcessingTest {
    @Test fun stereo48kIsDownmixedAndResampledTo16k() {
        val input=ShortArray(48000*2){if(it%2==0)1000 else 3000}
        val output=PcmResampler(48000,2).convert(input)
        assertEquals(16000,output.size);assertTrue(output.all{it==2000.toShort()})
    }
    @Test fun oddCodecBuffersPreserveStereoFramesAndFractionalSampleRate() {
        val pcm=ShortArray(44100*2){(it%30000).toShort()}
        val whole=PcmResampler(44100,2).convert(pcm)
        val stream=PcmResampler(44100,2)
        val chunks=pcm.toList().chunked(713).flatMap{stream.convert(it.toShortArray()).toList()}.toShortArray()
        assertEquals(16000,whole.size);assertArrayEquals(whole,chunks)
    }
    @Test fun telephoneRateAudioIsUpsampledWithoutChangingItsLevel() {
        val output=PcmResampler(8000,1).convert(ShortArray(8000){(-2000).toShort()})
        assertEquals(16000,output.size);assertTrue(output.all{it==(-2000).toShort()})
    }
    @Test fun onlyVoicePlaceholdersAreDetectedAsVoiceWithoutAMimeType() {
        assertTrue(VoiceMessageRules.placeholder("🎤 Mensaje de voz (0:12)"))
        assertTrue(VoiceMessageRules.placeholder("Nota de voz"))
        assertFalse(VoiceMessageRules.placeholder("Quiero enviar un audio sobre la campaña"))
    }
    @Test fun emptyOrLowConfidenceSpeechDoesNotAuthorizeAnInventedAnswer() {
        assertFalse(VoiceTranscript("",0.99,1000).reliable)
        assertFalse(VoiceTranscript("algo",0.30,1000).reliable)
        assertTrue(VoiceTranscript("hola",0.95,1000).reliable)
    }
    @Test fun oggAttachmentIsVoiceEvenWhenItsAccessibilityTextIsNotAPhrase() {
        val m=IncomingMessage("key","fp",Channel.WHATSAPP,"Visitante","",1,audioMime="application/ogg",audioUri="content://provider/note")
        assertTrue(m.voiceNote)
        assertFalse(m.copy(audioMime="image/jpeg").voiceNote)
    }
}
