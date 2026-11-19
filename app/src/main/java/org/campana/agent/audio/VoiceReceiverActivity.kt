package org.campana.agent.audio
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import org.campana.agent.CampaignApp

class VoiceReceiverActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);setContentView(TextView(this).apply{text="Recibiendo nota de voz para transcribir…";textSize=20f;setPadding(32,90,32,32)})
        val g=(application as CampaignApp).graph
        g.scope.launch{runCatching{g.voiceExport.receive(intent)}.onFailure{g.trace("voice_receive_rejected type="+it.javaClass.simpleName)};withContext(Dispatchers.Main){finish()}}
    }
}
