package org.campana.agent.audio

import android.media.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Creates only a short, app-owned Opus/OGG fixture from the phone's local TTS WAV. */
object OpusVoiceFixture {
    fun encode(wav:File,output:File) {
        val extractor=MediaExtractor();val pcm=ByteArrayOutputStream()
        try {extractor.setDataSource(wav.absolutePath);extractor.selectTrack(0);val f=extractor.getTrackFormat(0)
            check(f.getString(MediaFormat.KEY_MIME)=="audio/raw")
            val resampler=PcmResampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),48000)
            val b=ByteBuffer.allocate(32768).order(ByteOrder.LITTLE_ENDIAN)
            while(true){b.clear();val size=extractor.readSampleData(b,0);if(size<0)break;check(pcm.size()<4_000_000)
                b.position(0);b.limit(size);val samples=ShortArray(size/2);b.asShortBuffer().get(samples)
                val mono=resampler.convert(samples);val bytes=ByteBuffer.allocate(mono.size*2).order(ByteOrder.LITTLE_ENDIAN);bytes.asShortBuffer().put(mono);pcm.write(bytes.array());extractor.advance()
            }
        }finally{extractor.release()}
        val bytes=pcm.toByteArray();val encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val muxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var started=false
        try {val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS,48000,1);format.setInteger(MediaFormat.KEY_BIT_RATE,24000)
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            var offset=0;var track=-1;var eos=false;var done=false;val info=MediaCodec.BufferInfo();val deadline=android.os.SystemClock.elapsedRealtime()+30_000
            while(!done){check(android.os.SystemClock.elapsedRealtime()<deadline)
                if(!eos){val i=encoder.dequeueInputBuffer(10_000);if(i>=0){val buffer=checkNotNull(encoder.getInputBuffer(i));buffer.clear();val size=minOf(1920,bytes.size-offset,buffer.remaining())
                    if(size>0){buffer.put(bytes,offset,size);encoder.queueInputBuffer(i,0,size,offset/2*1_000_000L/48000,0);offset+=size}
                    else {encoder.queueInputBuffer(i,0,0,offset/2*1_000_000L/48000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true}
                }}
                val i=encoder.dequeueOutputBuffer(info,10_000)
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(encoder.outputFormat);muxer.start();started=true}
                else if(i>=0){if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){check(started);val buffer=checkNotNull(encoder.getOutputBuffer(i));buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(track,buffer,info)}
                    done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;encoder.releaseOutputBuffer(i,false)
                }
            }
        } finally {runCatching{encoder.stop()};encoder.release();if(started)runCatching{muxer.stop()};muxer.release()}
    }
}
