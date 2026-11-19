package org.campana.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.campana.agent.phone.PostLinkReceiverActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Passive fixtures: no UI input, social navigation, clipboard use or external sends. */
@RunWith(AndroidJUnit4::class)
class PostLinkSecurityTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun shareReceiverIsDisabledOutsideAnActiveVerifiedInspection() {
        val info=context.packageManager.getActivityInfo(ComponentName(context,PostLinkReceiverActivity::class.java),PackageManager.MATCH_DISABLED_COMPONENTS)
        assertTrue(info.exported);assertFalse(info.enabled)
    }
    @Test fun unsolicitedSharedLinksAndNestedIntentsCannotChangeCampaignMemory()=runBlocking {
        val g=(context.applicationContext as CampaignApp).graph;g.ready.await()
        assertTrue("La prueba pasiva exige la pausa conservada por el propietario",g.paused.value)
        val id=g.activeCampaign.value
        val before=g.dao.allReceipts(id).size
        assertFalse(g.postLinks.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://www.facebook.com/share/p/Example123/")))
        assertFalse(g.postLinks.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_INTENT,Intent(Intent.ACTION_VIEW))))
        assertEquals(before,g.dao.allReceipts(id).size)
    }
}
