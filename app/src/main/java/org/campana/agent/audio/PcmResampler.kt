package org.campana.agent.audio

/** Streaming area resampling with channel downmix; carries partial frames across codec buffers. */
class PcmResampler(private val inputRate:Int,private val channels:Int,private val outputRate:Int=16000) {
    init { require(inputRate in 8000..192000 && channels in 1..8 && outputRate>0) }
    private var channel=0;private var frame=0.0
    private var weight=0.0;private var area=0.0
    fun convert(samples:ShortArray):ShortArray {
        val result=ArrayList<Short>()
        val width=inputRate.toDouble()/outputRate
        for(sample in samples) {
            frame+=sample.toDouble()/channels;channel++
            if(channel!=channels)continue
            var remaining=1.0
            while(remaining>1e-9) {
                val part=minOf(remaining,width-weight);area+=frame*part;weight+=part;remaining-=part
                if(weight>=width-1e-9){result.add((area/width).toInt().coerceIn(-32768,32767).toShort());weight=0.0;area=0.0}
            }
            frame=0.0;channel=0
        }
        return result.toShortArray()
    }
}
