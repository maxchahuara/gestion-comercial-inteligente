package org.campana.agent.agent

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.campana.agent.auth.IdentityValidator
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class IdentityValidatorTest {
    private val key=RSAKeyGenerator(2048).keyID("test").generate()
    private val jwks=JWKSet(key.toPublicJWK()).toString()
    private fun token(audience:String="app_test",nonce:String="pending",expires:Long=System.currentTimeMillis()+60000,issuer:String="https://auth.openai.com"):String {
        val claims=JWTClaimsSet.Builder().issuer(issuer).subject("owner").audience(audience).expirationTime(Date(expires))
            .claim("nonce",nonce).claim("email","test@example.invalid").build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims).apply { sign(RSASSASigner(key)) }.serialize()
    }
    @Test fun verifiesSignatureAudienceAndNonce() { assertEquals("owner",IdentityValidator.verify(token(),jwks,"app_test","pending").subject) }
    @Test fun rejectsAnotherClient() { assertThrows(IllegalArgumentException::class.java) { IdentityValidator.verify(token(audience="other"),jwks,"app_test","pending") } }
    @Test fun rejectsAnotherAttemptAndIssuer() {
        assertThrows(IllegalArgumentException::class.java) { IdentityValidator.verify(token(nonce="other"),jwks,"app_test","pending") }
        assertThrows(IllegalArgumentException::class.java) { IdentityValidator.verify(token(issuer="https://example.invalid"),jwks,"app_test","pending") }
    }
    @Test fun rejectsExpiredAndChangedSubjectOnRefresh() {
        assertThrows(IllegalArgumentException::class.java) { IdentityValidator.verify(token(expires=0),jwks,"app_test","pending") }
        assertThrows(IllegalArgumentException::class.java) { IdentityValidator.verify(token(),jwks,"app_test",null,"someone_else") }
    }
    @Test fun modifiedTokenCannotBeTrusted() {
        val parts=token().split(".")
        val forged=parts[0]+"."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"attacker","aud":"app_test","exp":9999999999}""".toByteArray())+"."+parts[2]
        assertThrows(IllegalArgumentException::class.java) { IdentityValidator.verify(forged,jwks,"app_test","pending") }
    }
}
