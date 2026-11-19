package org.campana.agent.agent

/** Authorization generation, independent of any individual task or Android process. */
class SessionGate(initialActive:Boolean,initialEpoch:Long=0) {
    @Volatile var active=initialActive;private set
    @Volatile var epoch=initialEpoch;private set
    @Synchronized fun resume():Long {if(!active){epoch++;active=true};return epoch}
    @Synchronized fun recover():Long {if(active){active=false;epoch++};return epoch}
    fun permits(capturedEpoch:Long?)=active && capturedEpoch==epoch
}
