package org.campana.agent.jobs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.campana.agent.CampaignApp
import org.campana.agent.data.AgentTask
import java.util.concurrent.TimeUnit

class TaskWorker(context: Context, params:WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun getForegroundInfo():ForegroundInfo {
        val pause=PendingIntent.getBroadcast(applicationContext,71,Intent(applicationContext,PauseReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(applicationContext,"agent").setSmallIcon(org.campana.agent.R.drawable.ic_launcher)
            .setContentTitle("Agente ejecutando tu campaña").setContentText("Puedes pausar o recuperar el control en cualquier momento.")
            .setOngoing(true).addAction(0,"Pausar",pause).build()
        val type=if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        return ForegroundInfo(1000+(this.id.hashCode() and 0x3fffffff),notification,type)
    }
    override suspend fun doWork():Result {
        val g=(applicationContext as CampaignApp).graph
        return try {
            g.ready.await()
            g.runtime.ensureStarted("worker_recovery")
            if(g.paused.value)return Result.success()
            val id=inputData.getString("task")
            val tasks=if(id!=null)listOfNotNull(g.dao.task(id)).filter{it.status in setOf("QUEUED","WAITING_NETWORK","WAITING_UNLOCK","WAITING_PERMISSION") && it.dueAt<=System.currentTimeMillis()} else g.dao.dueTasks(System.currentTimeMillis())
            if(tasks.isEmpty())return Result.success()
            val replies=tasks.filter{it.kind=="REPLY" && it.notificationKey.isNotBlank() && it.assetId.isBlank()}
            replies.forEach{g.engine.runTask(it.id,foregroundManaged=true)}
            val visual=tasks-replies.toSet()
            if(visual.isEmpty())return Result.success()
            try {setForeground(getForegroundInfo())}
            catch(e:Exception) {
                visual.forEach{g.dao.waitBeforeStarting(it.id,"Abre la app para permitir la ejecución con aviso visible y pide continuar esta tarea.",System.currentTimeMillis())}
                return Result.success()
            }
            visual.forEach { g.engine.runTask(it.id,foregroundManaged=true) }
            Result.success()
        } catch (_:Exception) { Result.retry() }
    }
    companion object {
        fun kick(context:Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("task-wakeup",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TaskWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun schedule(context:Context,task:AgentTask,reviewed:Boolean=false) {
            val builder=OneTimeWorkRequestBuilder<TaskWorker>()
                .setInputData(workDataOf("task" to task.id))
                .setInitialDelay(maxOf(0,task.dueAt-System.currentTimeMillis()),TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            if(task.kind=="REPLY" && task.notificationKey.isNotBlank() && task.dueAt<=System.currentTimeMillis())
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            val request=builder.build()
            WorkManager.getInstance(context).enqueueUniqueWork("task-"+task.id,if(reviewed)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
        }
        fun scheduleSweep(context:Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("task-sweep",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TaskWorker>(15,TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED){TaskWorker.scheduleSweep(context);TaskWorker.kick(context)}
    }
}
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) { (context.applicationContext as CampaignApp).graph.pause(true) }
}
