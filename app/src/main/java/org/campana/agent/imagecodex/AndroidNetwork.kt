// Adapted from Lámina Android (Apache-2.0).
package org.campana.agent.imagecodex

import android.util.Base64
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * The official Linux/musl executable cannot use Android's netd DNS resolver or CA store.
 * This loopback-only CONNECT transport supplies Android DNS; TLS stays end-to-end inside Codex.
 * It never handles plaintext OpenAI requests or tokens, and it has no external listener.
 */
internal class AndroidNetwork : Closeable {
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "gci-image-network").apply { isDaemon = true } }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(24)
    private val secret = UUID.randomUUID().toString()
    private val credential = Base64.encodeToString("gci-image:$secret".toByteArray(), Base64.NO_WRAP)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val proxyUrl = "http://gci-image:$secret@127.0.0.1:${server.localPort}"
    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!slots.tryAcquire()) { socket.close(); continue }
                sockets.add(socket)
                executor.execute { connect(socket) }
            }
        }
    }
    private fun connect(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (bytes.size < 8192) {
                    val b = input.read()
                    if (b < 0 || b == 10) break
                    if (b != 13) bytes.add(b.toByte())
                }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            val first = line().split(' ')
            require(first.size == 3 && first[0] == "CONNECT")
            val authority = first[1]
            val host = authority.substringBeforeLast(':').lowercase()
            val port = authority.substringAfterLast(':').toInt()
            require(port == 443 && host.matches(Regex("[a-z0-9.-]+")))
            require(listOf("openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "oai.com").any { host == it || host.endsWith(".$it") })
            var authorised = false
            var count = 0
            while (true) {
                val header = line(); if (header.isBlank()) break
                require(++count <= 64)
                if (header.substringBefore(':').equals("proxy-authorization", true)) authorised = header.substringAfter(':').trim() == "Basic $credential"
            }
            require(authorised)
            // java.net resolves through Android's configured network (including VPN/private DNS).
            remote = Socket().apply { connect(InetSocketAddress(host, port), 30000); soTimeout = 20 * 60 * 1000 }
            sockets.add(remote)
            client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
            client.soTimeout = 20 * 60 * 1000
            val upstream = remote
            executor.execute { try { input.copyTo(upstream.getOutputStream()) } catch (_: Exception) {} finally { runCatching { upstream.close() }; runCatching { client.close() } } }
            upstream.getInputStream().copyTo(client.getOutputStream())
        } catch (_: Exception) {
            // Intentionally do not log CONNECT headers, credentials, or encrypted traffic.
        } finally {
            remote?.let { sockets.remove(it); runCatching { it.close() } }
            sockets.remove(client); runCatching { client.close() }; slots.release()
        }
    }
    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        executor.shutdownNow()
    }
    companion object {
        fun exportTrustedCertificates(output: File) {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            output.bufferedWriter(Charsets.US_ASCII).use { writer ->
                val aliases = store.aliases()
                while (aliases.hasMoreElements()) {
                    val cert = store.getCertificate(aliases.nextElement()) ?: continue
                    writer.appendLine("-----BEGIN CERTIFICATE-----")
                    Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).forEach { writer.appendLine(it) }
                    writer.appendLine("-----END CERTIFICATE-----")
                }
            }
        }
    }
}
