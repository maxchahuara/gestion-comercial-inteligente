package org.campana.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.campana.agent.net.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class QaTraceTest {
    @Test fun summarizeLatestFixtureActions()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("trace")=="true")
        val g=(ApplicationProvider.getApplicationContext<Context>() as CampaignApp).graph
        val campaign=g.dao.campaigns().first { it.name=="QA · adjunto image" }
        val records=g.dao.allReceipts(campaign.id)
        records.forEach { r->
            if(r.action.contains("screen_action") || r.action=="external_submit") {
                val detail=runCatching { json.parseToJsonElement(r.detail).jsonObject }.getOrNull()
                println("QA "+r.action+" args="+detail?.str("arguments")+" result="+detail?.str("result"))
            }
        }
    }
}
