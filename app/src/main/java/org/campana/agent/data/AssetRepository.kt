package org.campana.agent.data

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.campana.agent.net.json
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AssetRepository(private val context: Context, private val dao: CampaignDao) {
    private fun extension(mime:String):String=when(mime) {
        "image/jpeg"->"jpg";"image/png"->"png";"image/webp"->"webp";"application/pdf"->"pdf"
        "video/mp4"->"mp4";"audio/mpeg"->"mp3";"audio/ogg"->"ogg";"text/plain"->"txt"
        "text/csv"->"csv";"text/markdown"->"md";"application/json"->"json";else->"bin"
    }
    private val folder = File(context.filesDir,"assets").apply { mkdirs() }
    fun file(asset: Asset): File {
        require(asset.fileName.matches(Regex("[a-zA-Z0-9.-]+")))
        return File(folder,asset.fileName).also { require(it.canonicalFile.parentFile == folder.canonicalFile) }
    }
    fun uri(asset: Asset): Uri = FileProvider.getUriForFile(context,context.packageName+".files",file(asset),asset.name.replace(Regex("[/\\\\]"),"_"))
    suspend fun import(campaignId: String, uri: Uri): Asset = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Selecciona un archivo desde el selector del teléfono." }
        var name = "Archivo"
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if (it.moveToFirst()) name=it.getString(0).take(150)
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        require(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime in setOf("application/pdf","text/plain","text/markdown","text/csv","application/json","application/octet-stream")) { "Formato no admitido." }
        val fileName=newId()+"."+extension(mime); val target=File(folder,fileName)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { output ->
                    val buffer=ByteArray(8192); var total=0L
                    while (true) { val n=input.read(buffer); if(n<0)break; total+=n; require(total<=25*1024*1024) { "El archivo supera 25 MB." }; output.write(buffer,0,n) }
                }
            }
            val text = when {
                mime.startsWith("text/") || mime=="application/json" -> target.readText().take(60_000)
                mime=="application/pdf" -> pdfText(target)
                else -> ""
            }
            Asset(campaignId=campaignId,name=name,mime=mime,fileName=fileName,extractedText=text)
                .also { dao.insert(it) }
        } catch(e:Exception) { target.delete(); throw e }
    }
    private fun pdfText(file: File): String {
        if (Build.VERSION.SDK_INT<35) return ""
        return runCatching {
            PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                buildString {
                    for (i in 0 until minOf(renderer.pageCount,30)) {
                        renderer.openPage(i).use { page -> val text=page.textContents.joinToString("\n") { it.text };if(text.isNotBlank())append("Página ").append(i+1).append(": ").append(text).append('\n') }
                        if (length>60_000) break
                    }
                }.take(60_000)
            }
        }.getOrDefault("")
    }
    suspend fun saveGeneratedImage(campaignId: String, bytes: ByteArray, label: String = "Imagen generada"): Asset = withContext(Dispatchers.IO) {
        checkNotNull(dao.campaign(campaignId))
        require(bytes.size in 16..12_000_000) { "El archivo de imagen supera el límite permitido." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/jpeg","image/png") && bounds.outWidth in 256..2048 && bounds.outHeight in 256..2048) {
            "La imagen recibida no es un PNG o JPEG de campaña válido."
        }
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("La imagen recibida no se puede abrir.")
        image.recycle()
        val id = newId()
        val extension = if(bounds.outMimeType == "image/png") "png" else "jpg"
        val asset = Asset(id, campaignId, "$label.$extension", bounds.outMimeType, "$id.$extension")
        val target = file(asset)
        try {
            target.writeBytes(bytes)
            dao.insert(asset)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        asset
    }
    suspend fun createImage(campaignId: String, title: String, body: String): Asset = withContext(Dispatchers.IO) {
        val bitmap=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(Color.rgb(248,247,242))
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.color=Color.rgb(23,96,76); canvas.drawRect(0f,0f,1080f,20f,p)
        fun draw(text:String,size:Float,y:Float,bold:Boolean):Int {
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(24,36,31); textSize=size; typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,912).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(12f,1f).build()
            canvas.save(); canvas.translate(84f,y); layout.draw(canvas); canvas.restore(); return layout.height
        }
        val height=draw(title.take(120),64f,100f,true)
        draw(body.take(500),38f,150f+height,false)
        val id=newId(); val name=id+".png"; val target=File(folder,name)
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        Asset(id,campaignId,"Pieza de campaña.png","image/png",name).also { dao.insert(it) }
    }
    suspend fun createPoster(campaignId:String,title:String,slogan:String,number:String,description:String,footer:String,emblem:String):Asset=withContext(Dispatchers.IO) {
        val bitmap=CampaignPoster.render(title,slogan,number,description,footer,emblem)
        val id=newId();val name=id+".png";val target=File(folder,name)
        try {target.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        Asset(id,campaignId,"Cartel de campaña.png","image/png",name).also{dao.insert(it)}
    }
    suspend fun backup(campaignId: String, password: CharArray): File = withContext(Dispatchers.IO) {
        require(password.size>=10) { "Usa una contraseña de al menos 10 caracteres." }
        val campaign=dao.campaign(campaignId) ?: error("Campaña no encontrada.")
        val assets=dao.assets(campaignId)
        require(assets.sumOf { file(it).length() }<=60L*1024*1024) { "La copia supera 60 MB; exporta menos archivos." }
        val archive=ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            fun add(name:String,bytes:ByteArray) { zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
            add("campaign.json",json.encodeToString(campaign).toByteArray())
            add("tasks.json",json.encodeToString(dao.tasks(campaignId).map { it.copy(notificationKey="",notificationFingerprint="",dedupeKey=null) }).toByteArray())
            add("leads.json",json.encodeToString(dao.leads(campaignId)).toByteArray())
            add("messages.json",json.encodeToString(dao.allMessages(campaignId)).toByteArray())
            add("receipts.json",json.encodeToString(dao.allReceipts(campaignId)).toByteArray())
            add("assets.json",json.encodeToString(assets).toByteArray())
            assets.forEach { add("assets/"+it.fileName,file(it).readBytes()) }
        }
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) }
        val spec=PBEKeySpec(password,salt,210_000,256)
        val key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES")) }
        key.fill(0)
        val folder=File(context.cacheDir,"exports").apply { mkdirs() }
        File(folder,"campana-"+newId()+".campana").apply { writeBytes("CMP1".toByteArray()+salt+cipher.iv+cipher.doFinal(archive.toByteArray())) }
    }
    suspend fun restore(uri:Uri,password:CharArray):Campaign = withContext(Dispatchers.IO) {
        require(uri.scheme=="content" && password.size>=10)
        val raw=context.contentResolver.openInputStream(uri)!!.use { input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=65*1024*1024) { "La copia supera el tamaño permitido." };out.write(buffer,0,n) };out.toByteArray()
        }
        require(raw.size>48 && String(raw.copyOfRange(0,4))=="CMP1") { "Este archivo no es una copia de esta aplicación." }
        val spec=PBEKeySpec(password,raw.copyOfRange(4,20),210_000,256)
        val key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,raw.copyOfRange(20,32))) }
        key.fill(0)
        val decrypted=try { cipher.doFinal(raw.copyOfRange(32,raw.size)) } catch(_:Exception) { error("Contraseña incorrecta o copia dañada.") }
        val entries=mutableMapOf<String,ByteArray>();var total=0
        ZipInputStream(ByteArrayInputStream(decrypted)).use { zip->
            while(true) {
                val entry=zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name !in entries && entries.size<1000)
                require(entry.name in setOf("campaign.json","tasks.json","leads.json","assets.json","messages.json","receipts.json") ||
                    entry.name.matches(Regex("assets/[a-zA-Z0-9.-]+"))) { "La copia contiene una ruta no permitida." }
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=zip.read(buffer);if(n<0)break;total+=n;require(total<=65*1024*1024);out.write(buffer,0,n) }
                entries[entry.name]=out.toByteArray()
            }
        }
        fun text(name:String)=String(entries[name] ?: error("Copia incompleta."),Charsets.UTF_8)
        val original=json.decodeFromString<Campaign>(text("campaign.json"))
        val assets=json.decodeFromString<List<Asset>>(text("assets.json"))
        val leads=json.decodeFromString<List<Lead>>(text("leads.json"))
        val tasks=json.decodeFromString<List<AgentTask>>(text("tasks.json"))
        val messages=entries["messages.json"]?.let { json.decodeFromString<List<ChatMessage>>(String(it,Charsets.UTF_8)) }.orEmpty()
        val receipts=entries["receipts.json"]?.let { json.decodeFromString<List<Receipt>>(String(it,Charsets.UTF_8)) }.orEmpty()
        require(assets.size<500 && tasks.size<1000 && leads.size<5000)
        val restored=original.copy(id=newId(),name=original.name+" · restaurada",mode="DRAFT",autoReply=false,allowPublishing=false,updatedAt=System.currentTimeMillis())
        val createdFiles=mutableListOf<File>();val assetMap=mutableMapOf<String,String>()
        try {
            dao.save(restored)
            for(asset in assets) {
                require(asset.fileName.matches(Regex("[a-zA-Z0-9.-]+")))
                val bytes=entries["assets/"+asset.fileName] ?: error("Falta un archivo de la copia.")
                val id=newId();val local=File(folder,id+"."+extension(asset.mime));local.writeBytes(bytes);createdFiles+=local
                dao.insert(asset.copy(id=id,campaignId=restored.id,fileName=local.name));assetMap[asset.id]=id
            }
            leads.forEach { dao.save(it.copy(id=newId(),campaignId=restored.id)) }
            val taskMap=mutableMapOf<String,String>()
            tasks.forEach { val id=newId();taskMap[it.id]=id;dao.insert(it.copy(id=id,campaignId=restored.id,status="DRAFT",assetId=assetMap[it.assetId] ?: "",dedupeKey=null,notificationKey="",notificationFingerprint="",detail="Restaurada como borrador. Revisa las reglas antes de ejecutar.")) }
            messages.forEach { dao.insert(it.copy(id=newId(),campaignId=restored.id)) }
            receipts.forEach { dao.insert(it.copy(id=newId(),campaignId=restored.id,taskId=taskMap[it.taskId] ?: "")) }
            restored
        } catch(e:Exception) {
            withContext(NonCancellable){dao.deleteCampaign(restored.id);createdFiles.forEach { it.delete() }};throw e
        }
    }
}
