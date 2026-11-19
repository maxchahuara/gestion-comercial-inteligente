package org.campana.agent.audio

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.campana.agent.AppGraph
import org.campana.agent.data.*
import org.campana.agent.net.*
import org.campana.agent.phone.IncomingMessage
import java.io.File

data class VoiceInput(val text:String,val clarification:String?=null,val confirmation:String?=null) {
    val directReply get()=clarification ?: confirmation
}
class IncomingVoice(private val g:AppGraph) {
    val transcriber=VoiceTranscriber(g.context)
    suspend fun unavailable(task:AgentTask,reason:String):VoiceInput {
        g.runtime.requireTask(task.id);val c=g.engine.authorized(task)
        val clarification="No pude acceder a una nota de voz para transcribirla. ¿Puedes escribir tu consulta sobre la campaña?"
        g.dao.insert(Receipt(campaignId=c.id,taskId=task.id,action="voice_transcript",status="UNAVAILABLE",detail=buildJsonObject{
            put("fingerprint",task.notificationFingerprint);put("context_updated_at",c.updatedAt);put("text","");put("clarification",clarification);put("reason_type",reason.take(100))
        }.toString()))
        g.trace("voice_transcription_unavailable type="+reason.take(80))
        return VoiceInput("",clarification)
    }
    suspend fun canRead(message:IncomingMessage):Boolean=withContext(Dispatchers.IO) {
        if(message.audioUri.isBlank())return@withContext false
        val uri=Uri.parse(message.audioUri)
        if(uri.scheme!="content" || g.context.packageManager.resolveContentProvider(uri.authority.orEmpty(),0)?.packageName!=message.channel.packageName)return@withContext false
        try {g.context.contentResolver.openAssetFileDescriptor(uri,"r")?.use{true} ?: false} catch(e:SecurityException){false} catch(e:java.io.IOException){false}
    }
    suspend fun input(task:AgentTask,message:IncomingMessage):VoiceInput {
        if(!message.voiceNote)return VoiceInput(message.text)
        val c=checkNotNull(g.dao.campaign(task.campaignId))
        g.dao.latestReceiptForTask(task.id,"voice_transcript")?.let{r->
            val d=json.parseToJsonElement(r.detail).jsonObject
            if(d.str("fingerprint")==task.notificationFingerprint && d.number("context_updated_at")==c.updatedAt)
                return VoiceInput(d.str("text"),d.str("clarification").takeIf{it.isNotBlank()},if(r.status=="TRANSCRIBED")VoiceReplyRules.confirmation(d.str("text")) else null)
        }
        val exported=withContext(Dispatchers.Main){g.voiceExport.file(task)}
        check(message.audioUri.isNotBlank() || exported!=null){"La nota de voz no incluye un archivo accesible en la notificación; hace falta obtenerla desde la conversación."}
        val uri=Uri.parse(message.audioUri)
        if(exported==null)check(uri.scheme=="content" && g.context.packageManager.resolveContentProvider(uri.authority.orEmpty(),0)?.packageName==message.channel.packageName){"El archivo de voz no pertenece a la app del mensaje."}
        val dir=File(g.context.cacheDir,"voice").apply{mkdirs()};val file=exported ?: File(dir,newId()+".audio")
        try {
            if(exported==null)withContext(Dispatchers.IO){g.context.contentResolver.openInputStream(uri)!!.use{input->file.outputStream().use{output->val b=ByteArray(16384);var bytes=0L
                while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;bytes+=n;require(bytes<=25*1024*1024);output.write(b,0,n)}
            }}}
            g.trace("voice_transcription_started channel="+message.channel)
            val result=transcriber.transcribe(file)
            g.runtime.requireTask(task.id)
            check(checkNotNull(g.dao.campaign(c.id)).updatedAt==c.updatedAt)
            val clarification=if(result.reliable)null else "No pude entender con suficiente claridad tu nota de voz. ¿Puedes repetirla o escribir tu consulta sobre la campaña?"
            g.dao.insert(Receipt(campaignId=c.id,taskId=task.id,action="voice_transcript",status=if(result.reliable)"TRANSCRIBED" else "UNCLEAR",detail=buildJsonObject{
                put("fingerprint",task.notificationFingerprint);put("context_updated_at",c.updatedAt);put("text",result.text);put("confidence",result.confidence);put("duration_ms",result.durationMillis);put("clarification",clarification.orEmpty());put("engine","vosk-small-es-0.42")
            }.toString()))
            g.dao.insert(ChatMessage(campaignId=c.id,role="notice",text=if(result.reliable)"Nota de voz transcrita en el celular (${task.channel}):\n\n"+result.text else "La nota de voz no se entendió con suficiente claridad; se solicitará repetirla o escribir."))
            g.trace("voice_transcription_finished reliable="+result.reliable)
            return VoiceInput(result.text,clarification,if(result.reliable)VoiceReplyRules.confirmation(result.text) else null)
        } catch(e:CancellationException){throw e} catch(e:Exception){return unavailable(task,e.javaClass.simpleName)} finally {file.delete()}
    }
}
