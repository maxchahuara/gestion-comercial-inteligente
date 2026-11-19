package org.campana.agent.audio

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.TextView
import kotlinx.coroutines.*
import org.campana.agent.CampaignApp
import java.io.File
import java.util.Locale

/** Entirely local speech fixture. No microphone, social UI, or external messages. */
class NativeVoiceQaActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val label=TextView(this).apply{text="Comprobando transcripción local de voz…";textSize=20f;setPadding(32,90,32,32)};setContentView(label)
        val g=(application as CampaignApp).graph
        g.scope.launch {
            val initialized=CompletableDeferred<Int>();val done=CompletableDeferred<Unit>()
            lateinit var tts:TextToSpeech
            withContext(Dispatchers.Main){tts=TextToSpeech(this@NativeVoiceQaActivity){initialized.complete(it)}}
            val file=File(cacheDir,"native-speech-fixture.wav")
            val opus=File(cacheDir,"native-speech-fixture.ogg")
            try {
                check(packageManager.getComponentEnabledSetting(android.content.ComponentName(this@NativeVoiceQaActivity,VoiceReceiverActivity::class.java))==android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                check(!g.voiceExport.receive(android.content.Intent(android.content.Intent.ACTION_SEND).setType("audio/ogg")))
                android.util.Log.i("CampanaVoiceQA","PASS unsolicited_audio_rejected=true receiver_disabled=true")
                check(withTimeout(30_000){initialized.await()}==TextToSpeech.SUCCESS)
                val voice=tts.voices.filter{it.locale.language=="es" && !it.isNetworkConnectionRequired}.firstOrNull() ?: error("No hay una voz española local para el ensayo.")
                withContext(Dispatchers.Main){tts.voice=voice;tts.setSpeechRate(0.85f);tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(id:String?){}
                    override fun onDone(id:String?){done.complete(Unit)}
                    @Deprecated("deprecated") override fun onError(id:String?){done.completeExceptionally(IllegalStateException("Falló la voz local de ensayo."))}
                });check(tts.synthesizeToFile("Hola, quiero información sobre la campaña. Qué número debo marcar.",Bundle(),file,"fixture")==TextToSpeech.SUCCESS)}
                withTimeout(30_000){done.await()}
                val result=g.voices.transcriber.transcribe(file)
                check(result.reliable && result.text.contains("campaña") && result.text.contains("número")){"La transcripción del ensayo no coincidió: ${result.text}"}
                android.util.Log.i("CampanaVoiceQA","PASS offline_model=true codec=true resampling=true transcript=${result.text} confidence=${result.confidence}")
                OpusVoiceFixture.encode(file,opus)
                val compressed=g.voices.transcriber.transcribe(opus)
                check(compressed.reliable && compressed.text.contains("campaña") && compressed.text.contains("número"))
                android.util.Log.i("CampanaVoiceQA","PASS opus_ogg=true transcript=${compressed.text} confidence=${compressed.confidence}")
                withContext(Dispatchers.Main){label.text="Transcripción local comprobada:\n\n"+result.text}
            }catch(e:Exception){android.util.Log.e("CampanaVoiceQA","FAIL "+e.message,e);withContext(Dispatchers.Main){label.text=e.message}}
            finally {tts.shutdown();file.delete();opus.delete()}
        }
    }
}
