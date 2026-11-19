package org.campana.agent.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
fun JsonObject.number(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0
fun form(values: Map<String,String>): String = values.entries.joinToString("&") {
    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
}
class NetworkFailure(message: String) : Exception(message)
fun safeError(error:Throwable):String = when {
    error is NetworkFailure -> error.message ?: "No se pudo comunicar con ChatGPT."
    error is java.io.IOException -> "Se interrumpió la conexión. Comprueba internet y vuelve a intentarlo."
    error is SecurityException -> "Se necesita un permiso del teléfono. Revisa Permisos y control en Ajustes."
    error.javaClass==IllegalStateException::class.java || error.javaClass==IllegalArgumentException::class.java ->
        (error.message ?: "No se pudo completar la operación.").take(500)
            .let { if(it.contains("eyJ") || it.contains("token",true) || it.contains("http") || it.contains("Input:"))"No se pudo validar la conexión o sus datos. Revisa la sesión y vuelve a intentarlo." else it }
    else -> "No se pudo completar la operación. Revisa la configuración e inténtalo de nuevo."
}

class Http {
    private val calls=java.util.concurrent.Executors.newCachedThreadPool{r->Thread(r,"Campana HTTPS").apply{isDaemon=true}}
    private suspend fun cancellable(block:(java.util.concurrent.atomic.AtomicReference<HttpsURLConnection>)->JsonObject):JsonObject=suspendCancellableCoroutine { continuation->
        val reference=java.util.concurrent.atomic.AtomicReference<HttpsURLConnection>()
        val future=calls.submit {
            if(continuation.isActive)try{val result=block(reference);if(continuation.isActive)continuation.resume(result)}catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
        }
        continuation.invokeOnCancellation{future.cancel(true);calls.execute{runCatching{reference.get()?.disconnect()}}}
    }
    private fun connection(url: String): HttpsURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.host in setOf("auth.openai.com", "api.openai.com")) { "Destino de red no permitido." }
        return (parsed.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 90_000
            setRequestProperty("Accept", "application/json")
        }
    }
    suspend fun request(url: String, token: String? = null, body: String? = null,
                        contentType: String = "application/json"): JsonObject = cancellable { reference ->
        val c = connection(url).also{reference.set(it)}
        try {
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw NetworkFailure("El servicio devolvió HTTP $code. Revisa conexión, sesión y límites de tu cuenta.")
            val text = c.inputStream.bufferedReader().use { it.readText().also { text -> require(text.length < 4_000_000) } }
            if (text.isBlank()) buildJsonObject {} else json.parseToJsonElement(text).jsonObject
        } finally { c.disconnect() }
    }
    suspend fun responses(token: String, body: JsonObject): JsonObject = cancellable { reference ->
        val c = connection("https://api.openai.com/v1/responses").also{reference.set(it)}
        try {
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw NetworkFailure("ChatGPT devolvió HTTP " + c.responseCode + ". Comprueba tu sesión y el uso autorizado.")
            val accumulator = ResponseStream()
            c.inputStream.bufferedReader().use { reader ->
                val event = StringBuilder()
                var line: String? = reader.readLine()
                while (line != null) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    if (line.isEmpty()) {
                        if (event.isNotEmpty()) accumulator.accept(event.toString().trimEnd())
                        event.setLength(0)
                    } else if (line.startsWith("data:")) {
                        event.append(line.removePrefix("data:").trimStart()).append('\n')
                        require(event.length < 4_000_000) { "Evento demasiado grande." }
                    }
                    line = reader.readLine()
                }
                if (event.isNotEmpty()) accumulator.accept(event.toString().trimEnd())
            }
            accumulator.result()
        } finally { c.disconnect() }
    }
}

class ResponseStream {
    private var completed: JsonObject? = null
    private var failed = false
    fun accept(data: String) {
        if (data == "[DONE]") return
        val event = json.parseToJsonElement(data).jsonObject
        when (event.str("type")) {
            "response.completed" -> completed = event["response"]?.jsonObject
            "response.failed", "response.incomplete", "error" -> failed = true
        }
    }
    fun result(): JsonObject {
        check(!failed) { "ChatGPT no completó la respuesta. Comprueba los límites de tu cuenta o reintenta." }
        return completed ?: throw java.io.EOFException("La conexión se interrumpió antes de completar la respuesta.")
    }
}
