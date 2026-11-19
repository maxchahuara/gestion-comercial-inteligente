package org.campana.agent.auth

import android.content.Context

object LocalDiagnostics {
    fun verifyRsa(context:Context):Boolean {
        // Public fixtures signed by a disposable test key. They authorize no service access.
        val token=context.assets.open("diagnostics/signed-fixture.txt").bufferedReader().use { it.readText().trim() }
        val jwks=context.assets.open("diagnostics/jwks.json").bufferedReader().use { it.readText() }
        val verified=IdentityValidator.verify(token,jwks,"campana-local-diagnostic","local-test-only")
        check(verified.subject=="local-diagnostic-only")
        return true
    }
}
