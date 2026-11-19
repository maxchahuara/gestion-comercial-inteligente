package org.campana.agent.jobs

import android.content.Context
import androidx.work.*
import kotlinx.serialization.json.*
import org.campana.agent.CampaignApp
import org.campana.agent.net.*
import java.util.concurrent.TimeUnit

class IndependenceWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val g=(applicationContext as CampaignApp).graph
        val result=try {
            check(g.model.value.isNotBlank()) { "Selecciona un modelo en Conexiones." }
            val response=g.http.responses(g.auth.token(),buildJsonObject {
                put("model",g.model.value);put("store",false);put("stream",true)
                putJsonArray("input") { add(buildJsonObject {
                    put("role","user");put("content","Diagnóstico técnico sin datos personales. Responde exactamente: VERIFICADO_DESDE_EL_CELULAR")
                }) }
            })
            val text=response["output"]?.jsonArray.orEmpty().flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
                .map { it.jsonObject }.joinToString("") { it.str("text") }
            check(text.trim()=="VERIFICADO_DESDE_EL_CELULAR") { "La respuesta del diagnóstico no coincidió." }
            "Comprobado: el teléfono ejecutó una consulta directa a ChatGPT."
        } catch(e:Exception) { safeError(e) }
        g.prefs.edit().putString("independence_result",result).putLong("independence_time",System.currentTimeMillis()).commit()
        g.status.value=result
        return Result.success()
    }
    companion object {
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("independence-check",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndependenceWorker>().setInitialDelay(25,TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
