package com.rokid.glass.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechNetworkPolicyTest {
    @Test fun phoneRelayDoesNotRequireGlassesInternet() {
        assertTrue(SpeechNetworkPolicy.canAttempt(0, false))
    }
    @Test fun directRouteRejectsKnownMissingInternet() {
        assertFalse(SpeechNetworkPolicy.canAttempt(1, false))
        assertTrue(SpeechNetworkPolicy.canAttempt(1, true))
    }
    @Test fun unknownStateDoesNotMeanOffline() {
        assertTrue(SpeechNetworkPolicy.canAttempt(null, false))
        assertTrue(SpeechNetworkPolicy.canAttempt(9, false))
        assertTrue(SpeechNetworkPolicy.canAttempt(1, null))
    }
}
