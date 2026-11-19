package org.campana.agent

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.campana.agent.data.Campaign
import org.junit.Assume.assumeTrue
import org.junit.Test

class AttachmentPreparationTest {
    @Test fun prepareImageSelectorWithoutSending()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prepare")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val g=(context.applicationContext as CampaignApp).graph
        val c=Campaign(name="QA · imagen local",objective="Preparar adjunto de prueba",mode="DRAFT")
        g.dao.save(c)
        val asset=g.assets.createImage(c.id,"Prueba de Campaña","Imagen de prueba creada dentro del celular.")
        val uri=g.assets.uri(asset)
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                setPackage("com.whatsapp");type="image/png";putExtra(Intent.EXTRA_STREAM,uri)
                putExtra(Intent.EXTRA_TEXT,"Prueba de imagen local de Campaña.")
                clipData=ClipData.newRawUri(asset.name,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        delay(500)
    }
}
