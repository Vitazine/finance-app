package app.finance.android

import app.finance.android.ai.NativeBridge
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class NativeLibraryLoadTest {
    @Test fun packagedRuntimeCanInitializeAndReleaseWithoutWeights() {
        Assume.assumeTrue("Native build only", BuildConfig.NATIVE_AI)
        val handle = NativeBridge.create()
        try {
            assertTrue(handle != 0L)
        } finally {
            if (handle != 0L) NativeBridge.close(handle)
        }
    }
}
