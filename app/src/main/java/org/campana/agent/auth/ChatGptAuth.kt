package org.campana.agent.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.campana.agent.R
import org.campana.agent.data.newId
import org.campana.agent.net.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.*

class ChatGptAuth(private val context: Context, val vault: SessionVault, private val http: Http) {
    private val refreshLock = Mutex()
    private val loginLock = Mutex()
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    suspend fun login(existingClient: String? = null): Session = loginLock.withLock {
        withContext(Dispatchers.IO) {
            val appName = context.getString(R.string.app_name)
            val previous = vault.read().sessions.firstOrNull { it.clientId == existingClient }
            val host = prefs.getString("host_id", null) ?: ("urn:uuid:" + newId()).also { prefs.edit().putString("host_id", it).commit() }
            val state = random(); val nonce = random(); val verifier = random()
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 1000
            try {
                val callback = "http://127.0.0.1:" + server.localPort + "/auth/callback"
                val parameters = linkedMapOf(
                    "client_id" to (previous?.clientId ?: "dynamic_agent_client"), "ext_agent_host_id" to host,
                    "response_type" to "code", "redirect_uri" to callback,
                    "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                    "resource" to "https://api.openai.com/v1", "state" to state, "nonce" to nonce,
                    "code_challenge_method" to "S256", "code_challenge" to challenge
                )
                if (previous == null) parameters["agent_name_hint"] = appName
                if (!previous?.idToken.isNullOrBlank()) parameters["id_token_hint"] = previous!!.idToken
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://auth.openai.com/api/accounts/authorize?" + form(parameters)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                withTimeout(240_000) {
                    var result: Session? = null
                    while (result == null) {
                        ensureActive()
                        val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                        socket.use { s ->
                            s.soTimeout = 3000
                            val line = s.getInputStream().bufferedReader().readLine()?.take(16384) ?: return@use
                            val path = line.split(" ").getOrNull(1) ?: ""
                            val uri = Uri.parse("http://127.0.0.1" + path)
                            if (!line.startsWith("GET ") || uri.path != "/auth/callback" ||
                                !IdentityValidator.equalSecret(uri.getQueryParameter("state") ?: "", state)) {
                                s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                return@use
                            }
                            val html = "<html lang=\"es\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><body><h2>${TextUtils.htmlEncode(appName)}</h2><p>Puedes volver a la aplicación. Estamos comprobando la conexión.</p></body></html>"
                            val bytes = html.toByteArray(Charsets.UTF_8)
                            s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Security-Policy: default-src 'none'\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                            require(uri.getQueryParameter("error") == null) { "No se autorizó el acceso. Puedes volver a intentarlo." }
                            val client = uri.getQueryParameter("client_id") ?: previous?.clientId ?: error("El registro no devolvió la identidad de la aplicación.")
                            require(client != "dynamic_agent_client" && (previous == null || client == previous.clientId)) { "Registro de aplicación no coincidente." }
                            val token = http.request("https://auth.openai.com/api/accounts/oauth/token", body = form(mapOf(
                                "grant_type" to "authorization_code", "client_id" to client,
                                "code" to (uri.getQueryParameter("code") ?: error("Falta el código de acceso.")),
                                "code_verifier" to verifier, "redirect_uri" to callback, "resource" to "https://api.openai.com/v1"
                            )), contentType = "application/x-www-form-urlencoded")
                            val jwks = http.request("https://auth.openai.com/.well-known/jwks.json")
                            val identity = IdentityValidator.verify(token.str("id_token"), jwks.toString(), client, nonce, previous?.subject)
                            result = Session(client, identity.subject, identity.email, token.str("access_token"),
                                token.str("refresh_token"), token.str("id_token"),
                                System.currentTimeMillis() + token.number("expires_in") * 1000, token.str("scope"))
                            require(result!!.accessToken.isNotBlank() && result!!.refreshToken.isNotBlank()) { "Credenciales incompletas." }
                            vault.put(result!!)
                            withContext(Dispatchers.Main) {
                                runCatching { context.startActivity(Intent(context,org.campana.agent.MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                            }
                        }
                    }
                    result!!
                }
            } finally { server.close() }
        }
    }
    suspend fun token(): String = refreshLock.withLock {
        var session = vault.active() ?: error("Conecta tu cuenta de ChatGPT en Conexiones.")
        require("chatgpt.tokens.use.direct" in session.scopes.split(" ")) { "La cuenta no autorizó el uso del plan de ChatGPT." }
        if (session.expiresAt < System.currentTimeMillis() + 90_000) {
            val next = http.request("https://auth.openai.com/api/accounts/oauth/token", body=form(mapOf(
                "grant_type" to "refresh_token", "client_id" to session.clientId,
                "refresh_token" to session.refreshToken, "resource" to "https://api.openai.com/v1"
            )), contentType="application/x-www-form-urlencoded")
            val idToken = next.str("id_token")
            if (idToken.isNotBlank()) IdentityValidator.verify(idToken,
                http.request("https://auth.openai.com/.well-known/jwks.json").toString(),
                session.clientId, null, session.subject)
            session = session.copy(accessToken=next.str("access_token"),
                refreshToken=next.str("refresh_token").ifBlank { session.refreshToken },
                idToken=idToken.ifBlank { session.idToken }, scopes=next.str("scope").ifBlank { session.scopes },
                expiresAt=System.currentTimeMillis() + next.number("expires_in") * 1000)
            require(session.accessToken.isNotBlank())
            vault.put(session)
        }
        require("chatgpt.tokens.use.direct" in session.scopes.split(" "))
        session.accessToken
    }
    suspend fun signOut(): Boolean = refreshLock.withLock {
        val session = vault.active() ?: return@withLock true
        val revoked = runCatching { http.request("https://auth.openai.com/api/accounts/oauth/revoke", body=form(mapOf(
            "token" to session.refreshToken, "token_type_hint" to "refresh_token", "client_id" to session.clientId
        )), contentType="application/x-www-form-urlencoded") }.isSuccess
        vault.put(session.copy(accessToken="",refreshToken="",idToken="",expiresAt=0,scopes=""))
        revoked
    }
    suspend fun models(): List<Pair<String,String>> {
        val response = http.request("https://api.openai.com/v1/models", token())
        return response["models"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.str("visibility") == "list" }.map { it.str("slug") to it.str("display_name") }
    }
}
