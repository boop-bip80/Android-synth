package com.orbital.synth.audio

import org.junit.Assert.*
import org.junit.Test

class AudioFailureRecoveryPolicyTest {
    private fun AudioFailureRecoveryPolicy.claim(
        unexpected: Boolean = true,
        currentGeneration: Boolean = true,
        requested: Boolean = true,
        suspended: Boolean = false,
        disposed: Boolean = false,
    ) = claimRetry(unexpected, currentGeneration, requested, suspended, disposed)

    @Test fun firstUnexpectedForegroundFaultMayRetryButSecondCannotLoop() {
        val policy = AudioFailureRecoveryPolicy()
        assertTrue(policy.claim())
        assertFalse("A second AudioTrack error must stop, never spin-reopen", policy.claim())
    }

    @Test fun staleStoppedOrFocusSuspendedWorkerCannotRevivePlayback() {
        val policy = AudioFailureRecoveryPolicy()
        assertFalse(policy.claim(unexpected = false))
        assertFalse(policy.claim(currentGeneration = false))
        assertFalse(policy.claim(requested = false))
        assertFalse(policy.claim(suspended = true))
        assertFalse(policy.claim(disposed = true))
        // Disallowed failures must not consume the sole eligible retry.
        assertTrue(policy.claim())
        assertFalse(policy.claim())
    }

    @Test fun explicitStopStartsFreshBoundedForegroundBudget() {
        val policy = AudioFailureRecoveryPolicy()
        assertTrue(policy.claim())
        assertFalse(policy.claim())
        policy.resetOnExplicitStop()
        assertTrue(policy.claim())
        assertFalse(policy.claim())
    }
}
