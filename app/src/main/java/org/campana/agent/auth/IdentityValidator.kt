package org.campana.agent.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest

data class VerifiedIdentity(val subject: String, val email: String)
object IdentityValidator {
    fun equalSecret(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    fun verify(token: String, jwks: String, clientId: String, nonce: String?,
               expectedSubject: String? = null, now: Long = System.currentTimeMillis()): VerifiedIdentity {
        val jwt = SignedJWT.parse(token)
        require(jwt.header.algorithm == JWSAlgorithm.RS256) { "Firma de identidad no admitida." }
        val key = JWKSet.parse(jwks).keys.filterIsInstance<RSAKey>()
            .firstOrNull { it.keyID == jwt.header.keyID } ?: error("Clave de identidad desconocida.")
        require(jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) { "Firma de identidad inválida." }
        val c = jwt.jwtClaimsSet
        require(c.issuer == "https://auth.openai.com") { "Emisor inválido." }
        require(c.audience.contains(clientId)) { "Identidad emitida para otra aplicación." }
        require(c.expirationTime != null && c.expirationTime.time > now - 30_000) { "Identidad vencida." }
        require(c.issueTime == null || c.issueTime.time <= now + 60_000) { "Fecha de identidad inválida." }
        if (nonce != null) require(equalSecret(c.getStringClaim("nonce") ?: "", nonce)) { "Intento de acceso no coincidente." }
        require(!c.subject.isNullOrBlank()) { "Identidad incompleta." }
        if (expectedSubject != null) require(c.subject == expectedSubject) { "La cuenta no coincide." }
        return VerifiedIdentity(c.subject, c.getStringClaim("email") ?: "")
    }
}
