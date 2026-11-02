package app.finance.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceDiagnosticsTest {
    @Test fun longerDeadlineRequiresExplicitDebugOptIn() {
        assertEquals(120_000L, GenerationOptions().effective(debug = true).timeoutMs)
        assertEquals(
            600_000L,
            GenerationOptions(diagnostic = true).effective(debug = true).timeoutMs
        )
        assertEquals(
            120_000L,
            GenerationOptions(diagnostic = true).effective(debug = false).timeoutMs
        )
        assertEquals(
            false,
            GenerationOptions(diagnostic = true).effective(debug = false).diagnostic
        )
    }

    @Test fun prefillAndFirstTokenAbsenceAreRepresentedHonestly() {
        val snapshot = GenerationProgress.fromNative(
            longArrayOf(2, 900, 256, 0, 0, 0, 12_000_000),
            4096
        )
        assertEquals(GenerationPhase.PREFILL, snapshot.phase)
        assertEquals(900, snapshot.promptTokens)
        assertEquals(256, snapshot.processedTokens)
        assertEquals(12_000L, snapshot.elapsedMs)
        assertNull(snapshot.firstTokenMs)
        assertNull(snapshot.tokensPerSecond)
        assertEquals(4096, snapshot.pssKiB)
    }

    @Test fun tokenRateUsesMeasuredTimeAfterFirstToken() {
        val snapshot = GenerationProgress.fromNative(
            longArrayOf(3, 900, 900, 1_000_000, 0, 3, 2_000_000),
            8192
        )
        assertEquals(GenerationPhase.GENERATING, snapshot.phase)
        assertEquals(1000L, snapshot.firstTokenMs)
        assertEquals(2.0, snapshot.tokensPerSecond!!, 0.0001)
    }

    @Test fun missingNegativeAndOversizedCountersStayBounded() {
        val missing = GenerationProgress.fromNative(longArrayOf(), -1)
        assertEquals(GenerationPhase.PREPARING, missing.phase)
        assertEquals(0, missing.pssKiB)
        assertNull(missing.firstTokenMs)
        val invalid = GenerationProgress.fromNative(longArrayOf(2, 9_999, 99999, -1, 0, 999, -1), 0)
        assertEquals(2048, invalid.promptTokens)
        assertEquals(2048, invalid.processedTokens)
        assertEquals(256, invalid.outputTokens)
        assertEquals(0L, invalid.elapsedMs)
        assertNull(invalid.tokensPerSecond)
    }
}
