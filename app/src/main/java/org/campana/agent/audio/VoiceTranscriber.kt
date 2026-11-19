package org.campana.agent.audio

import android.content.Context
import android.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.campana.agent.net.json
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder

data class VoiceTranscript(val text:String,val confidence:Double,val durationMillis:Long) {
    val reliable get()=text.length>=2 && confidence>=0.55
}
class VoiceTranscriber(private val context:Context) {
    private val lock=Mutex();private var model:Model?=null
    suspend fun transcribe(file:File):VoiceTranscript=withContext(Dispatchers.IO){lock.withLock {
        require(file.length() in 1..25*1024*1024){"Audio vacío o mayor de 25 MB."}
        val speech=model ?: Model(VoskModelStore.ensure(context).absolutePath).also{model=it}
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        try {
            extractor.setDataSource(file.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true} ?: error("El archivo no contiene audio compatible.")
            extractor.selectTrack(track);val format=extractor.getTrackFormat(track)
            if(format.containsKey(MediaFormat.KEY_DURATION))check(format.getLong(MediaFormat.KEY_DURATION)<=300_000_000){"La nota de voz supera cinco minutos."}
            val decoder=MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)));codec=decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format,null,null,0);decoder.start()
            var resampler=PcmResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE),format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var inputDone=false;var outputDone=false;var frames=0L;var lastOutput=android.os.SystemClock.elapsedRealtime()
            val texts=mutableListOf<String>();val scores=mutableListOf<Double>()
            fun collect(value:String){val d=json.parseToJsonElement(value).jsonObject;d["text"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}?.let{texts.add(it)};d["result"]?.jsonArray?.forEach{it.jsonObject["conf"]?.jsonPrimitive?.doubleOrNull?.let(scores::add)}}
            Recognizer(speech,16000f).use{recognizer->recognizer.setWords(true);val info=MediaCodec.BufferInfo()
                while(!outputDone) {
                    currentCoroutineContext().ensureActive();check(android.os.SystemClock.elapsedRealtime()-lastOutput<30_000){"El audio no pudo decodificarse."}
                    if(!inputDone){val index=decoder.dequeueInputBuffer(10_000);if(index>=0){val buffer=checkNotNull(decoder.getInputBuffer(index));val n=extractor.readSampleData(buffer,0)
                        if(n<0){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else {decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0);extractor.advance()}
                    }}
                    val index=decoder.dequeueOutputBuffer(info,10_000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){val f=decoder.outputFormat
                        check(!f.containsKey(MediaFormat.KEY_PCM_ENCODING) || f.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_16BIT){"Codificación PCM no compatible."}
                        resampler=PcmResampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    } else if(index>=0) {
                        lastOutput=android.os.SystemClock.elapsedRealtime()
                        if(info.size>0){val b=checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size)
                            val shorts=ShortArray(info.size/2);b.asShortBuffer().get(shorts);val pcm=resampler.convert(shorts);frames+=pcm.size
                            check(frames<=16000L*300){"La nota de voz supera cinco minutos."}
                            if(pcm.isNotEmpty() && recognizer.acceptWaveForm(pcm,pcm.size))collect(recognizer.result)
                        }
                        outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;decoder.releaseOutputBuffer(index,false)
                    }
                };collect(recognizer.finalResult)
            }
            VoiceTranscript(texts.joinToString(" ").take(6000),if(scores.isEmpty())0.0 else scores.average(),frames*1000/16000)
        } finally {runCatching{codec?.stop()};codec?.release();extractor.release()}
    }}
}
