package org.campana.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskInterruptionTest {
    @Test fun takingControlNeverMakesAnEffectfulTaskRetryable() {
        for(network in listOf(false,true)) {
            assertEquals("UNCERTAIN",interruptedTaskStatus("SUBMITTING",network))
            assertEquals("SUBMITTED",interruptedTaskStatus("SUBMITTED",network))
            assertEquals("UNCERTAIN",interruptedTaskStatus("UNCERTAIN",network))
            assertEquals("CANCELLED",interruptedTaskStatus("CANCELLED",network))
        }
    }
    @Test fun interruptionBeforeSubmissionNeedsOwnerReviewOrInternet() {
        assertEquals("WAITING_OWNER",interruptedTaskStatus("RUNNING"))
        assertEquals("WAITING_NETWORK",interruptedTaskStatus("RUNNING",true))
    }
}
