package org.campana.agent.agent
import org.junit.Assert.*
import org.junit.Test
class SessionGateTest {
    @Test fun missingObservationEpochCannotBorrowTheInitialZeroEpoch(){val gate=SessionGate(true,0);assertFalse(gate.permits(null));assertTrue(gate.permits(0));gate.recover();assertFalse(gate.permits(0))}
    @Test fun anIdleSessionRemainsAuthorizedUntilOwnerRecovery(){val gate=SessionGate(true);val epoch=gate.epoch;repeat(100){assertTrue(gate.permits(epoch))};gate.recover();assertFalse(gate.permits(epoch))}
    @Test fun aLateAnswerCannotRegainAuthorityAfterPauseAndResume(){val gate=SessionGate(true,7);val old=gate.epoch;gate.recover();gate.resume();assertTrue(gate.active);assertFalse(gate.permits(old));assertTrue(gate.permits(gate.epoch))}
    @Test fun repeatedRecoveryNeverReactivatesTheAgent(){val gate=SessionGate(true);gate.recover();val epoch=gate.epoch;gate.recover();assertFalse(gate.active);assertEquals(epoch,gate.epoch)}
    @Test fun aPersistedManualStateNeedsExplicitResume(){val gate=SessionGate(false,19);assertFalse(gate.permits(19));gate.resume();assertTrue(gate.permits(20))}
}
