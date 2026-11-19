package org.campana.agent.jobs

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.campana.agent.CampaignApp
import org.campana.agent.MainActivity
import org.campana.agent.R

/** Visible, phone-owned lifetime of the user-authorized agent session. */
class AgentRuntimeService:Service() {
    private val jobs=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val g get()=(application as CampaignApp).graph
    private var loop:Job?=null
    private fun notification():Notification {
        val stop=PendingIntent.getBroadcast(this,901,Intent(this,PauseReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,902,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,"agent").setSmallIcon(R.drawable.ic_launcher).setOngoing(true)
            .setContentTitle("Agente de campaña activo").setContentText(g.runtime.phase.value)
            .setContentIntent(open).addAction(0,"Recuperar control",stop).setOnlyAlertOnce(true).build()
    }
    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT>=34)startForeground(900,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(900,notification())
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(g.paused.value){stopSelf();return START_NOT_STICKY}
        if(loop?.isActive!=true)loop=jobs.launch {
            g.ready.await();if(g.paused.value){stopSelf();return@launch}
            g.runtime.attach(this@AgentRuntimeService)
            while(isActive && !g.paused.value) {
                runCatching{g.runtime.tick()}.onFailure{g.trace("runtime_tick_error type="+it.javaClass.simpleName)}
                getSystemService(NotificationManager::class.java).notify(900,notification())
                delay(2_000)
            }
        }
        return START_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){jobs.cancel();g.runtime.detach(this);super.onDestroy()}
    override fun onTaskRemoved(rootIntent:Intent?){if(!g.paused.value)TaskWorker.kick(this);super.onTaskRemoved(rootIntent)}
}
