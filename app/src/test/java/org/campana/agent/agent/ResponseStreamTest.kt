package org.campana.agent.agent

import org.campana.agent.net.ResponseStream
import org.campana.agent.net.str
import org.junit.Assert.*
import org.junit.Test

class ResponseStreamTest {
    @Test fun requiresTerminalCompletion() {
        val stream = ResponseStream()
        stream.accept("""{"type":"response.output_text.delta","delta":"Parcial"}""")
        assertThrows(java.io.EOFException::class.java) { stream.result() }
    }
    @Test fun completionReturnsFullOutput() {
        val stream = ResponseStream()
        stream.accept("""{"type":"response.completed","response":{"id":"r1","output":[]}}""")
        stream.accept("[DONE]")
        assertEquals("r1",stream.result().str("id"))
    }
    @Test fun lateUsageFailureCannotBeTreatedAsSuccess() {
        val stream = ResponseStream()
        stream.accept("""{"type":"response.output_text.delta","delta":"Parcial"}""")
        stream.accept("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""")
        assertThrows(IllegalStateException::class.java) { stream.result() }
    }
}
