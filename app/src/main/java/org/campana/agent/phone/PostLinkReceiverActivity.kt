package org.campana.agent.phone

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import org.campana.agent.CampaignApp

/** A short-lived text-only share sink; never forwards incoming Intents or opens URIs. */
class PostLinkReceiverActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply{setText(org.campana.agent.R.string.receiving_post_link);textSize=18f;setPadding(32,48,32,32)})
        val g=(application as CampaignApp).graph
        g.scope.launch {
            runCatching{g.postLinks.receive(intent)}
            withContext(Dispatchers.Main){finish()}
        }
    }
}
