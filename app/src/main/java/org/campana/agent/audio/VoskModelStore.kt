package org.campana.agent.audio

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object VoskModelStore {
    const val NAME="vosk-model-small-es-0.42"
    const val SHA256="09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
    suspend fun ensure(context:Context):File=withContext(Dispatchers.IO) {
        val root=File(context.noBackupFilesDir,"speech").apply{mkdirs()}
        val model=File(root,NAME);val marker=File(model,".verified")
        if(marker.isFile && marker.readText()==SHA256 && File(model,"am/final.mdl").isFile)return@withContext model
        val staging=File(root,"extracting")
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            context.assets.open("$NAME.zip").use{input->val b=ByteArray(32768);var total=0L
                while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;total+=n;check(total<=45_000_000);digest.update(b,0,n)}
            }
            check(digest.digest().joinToString(""){"%02x".format(it)}==SHA256){"El modelo de español no supera la verificación."}
            // All recursive operations target these fixed app-private children only.
            staging.deleteRecursively();staging.mkdirs();var bytes=0L;var entries=0
            ZipInputStream(context.assets.open("$NAME.zip")).use{input->while(true){currentCoroutineContext().ensureActive();val e=input.nextEntry ?: break
                check(++entries<=100 && e.name.startsWith("$NAME/") && !e.name.contains('\\'))
                val target=File(staging,e.name).canonicalFile
                check(target.path.startsWith(staging.canonicalPath+File.separator)){"Ruta de modelo no válida."}
                if(e.isDirectory)target.mkdirs() else {target.parentFile!!.mkdirs();target.outputStream().use{output->val b=ByteArray(32768)
                    while(true){val n=input.read(b);if(n<0)break;bytes+=n;check(bytes<=160_000_000);output.write(b,0,n)}
                }}
            }}
            val extracted=File(staging,NAME);check(File(extracted,"am/final.mdl").isFile && File(extracted,"conf/model.conf").isFile)
            model.deleteRecursively();check(extracted.renameTo(model));marker.writeText(SHA256)
            model
        } finally {File(root,"download.part").delete();staging.deleteRecursively()}
    }
}
