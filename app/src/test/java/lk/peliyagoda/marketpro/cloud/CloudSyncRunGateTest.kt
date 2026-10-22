package lk.peliyagoda.marketpro.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * WorkManager's periodic and immediate worker runs must never concurrently
 * upload from one Room outbox on a single application process.
 */
class CloudSyncRunGateTest {
    @Test fun simultaneousWorkersDoNotOverlap() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val unblockFirst = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            CloudSyncRunGate.runExclusive {
                val count = inFlight.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                firstEntered.complete(Unit)
                try {
                    unblockFirst.await()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        firstEntered.await()
        val second = async(Dispatchers.Default) {
            CloudSyncRunGate.runExclusive {
                val count = inFlight.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                inFlight.decrementAndGet()
            }
        }
        unblockFirst.complete(Unit)
        joinAll(first, second)
        assertEquals(0, inFlight.get())
        assertEquals(1, maximum.get())
    }

    @Test fun failedUploadReleasesGateForRetry() = runBlocking {
        val error = runCatching {
            CloudSyncRunGate.runExclusive { error("Simulated HTTP failure") }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        val retried = CloudSyncRunGate.runExclusive { "retry allowed" }
        assertEquals("retry allowed", retried)
    }
}
