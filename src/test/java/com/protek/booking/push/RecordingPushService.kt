package com.protek.booking.push

import com.protek.booking.service.PushNotificationService
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [PushNotificationService] that records what would have been sent instead of calling FCM,
 * installed over the real one with `QuarkusMock.installMockForType`.
 *
 * Hand-written rather than mocked because these tests assert on the *content* of a send —
 * which tokens, which reason code — and a stub that keeps the calls reads as the scenario. It can
 * also be told to report specific tokens as dead or transiently failed, which is the only way to
 * exercise the push's two failure paths without a real revoked subscription.
 *
 * Java tests read `push.sends.get(0).data()`, so [Sent] is a `@JvmRecord` (pattern 1) and
 * [sends] a `@JvmField` (`final`, because all-open would otherwise make it open, pattern 8).
 * `java.util` `Set.of`/`List.copyOf`/`Map.copyOf` are kept for their null and duplicate refusals.
 */
class RecordingPushService : PushNotificationService() {

    @JvmRecord
    data class Sent(val tokens: List<String>, val title: String?, val body: String?, val data: Map<String, String>,
                    val link: String? = null)

    /* Thread-safe because notifyWatchers delivers on a ManagedExecutor thread while the test
     * asserts on the main one — an ArrayList here was writing across that boundary unsynchronised. */
    @JvmField
    final val sends: MutableList<Sent> = CopyOnWriteArrayList()
    private val attempts = AtomicInteger()
    private var deadTokens: Set<String> = java.util.Set.of()
    private var failingTokens: Set<String> = java.util.Set.of()
    private var failure: RuntimeException? = null

    /* Overridden rather than setting the inherited config field: that field was package-private
     * when this was written (it is a public @JvmField since the service became Kotlin), and
     * widening it just so a test can reach it would trade production encapsulation for test
     * convenience. isConfigured() must be true or the notifier short-circuits before doing
     * anything worth asserting. */
    override fun isConfigured(): Boolean = true

    override fun vapidPublicKey(): String? = "test-vapid-public-key"

    /** Tokens FCM should report as UNREGISTERED/INVALID_ARGUMENT — the prune signal. */
    fun withDeadTokens(vararg tokens: String): RecordingPushService {
        this.deadTokens = java.util.Set.of(*tokens)
        return this
    }

    /** Tokens that fail transiently — must NOT be pruned. */
    fun withFailingTokens(vararg tokens: String): RecordingPushService {
        this.failingTokens = java.util.Set.of(*tokens)
        return this
    }

    /** Makes the whole send blow up, for asserting a push failure cannot break a committed booking. */
    fun withTotalFailure(e: RuntimeException?): RecordingPushService {
        this.failure = e
        return this
    }

    fun allNotifiedTokens(): List<String> {
        val all = ArrayList<String>()
        sends.forEach { s -> all.addAll(s.tokens) }
        return all
    }

    /** Forgets every send and attempt so far, for a test whose setup sends something of its own. */
    fun clear() {
        sends.clear()
        attempts.set(0)
    }

    /** Sends attempted, including ones [withTotalFailure] made throw. */
    fun attempts(): Int = attempts.get()

    /**
     * Blocks until at least `expected` sends have been attempted.
     *
     * For the one test that must go through
     * [com.protek.booking.service.SeatAvailabilityNotifier.notifyWatchers]: that dispatches to a
     * ManagedExecutor and returns, so its send is still in flight when the test method ends.
     * `@BeforeEach` then installs a fresh recorder for the next test and the stray send lands
     * in *that* one — which is how `deliverSendsToTheWatchersOfThatSeatMapOnly` came to
     * fail on `[token-any, token-watching]`, a token it never registered. Measured: the stray
     * arrived 19 ms after the previous test returned and 192 ms before the next assertion, so it
     * reproduced on every Testcontainers run while passing against a faster local PostgreSQL.
     */
    fun awaitAttempts(expected: Int, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (attempts.get() < expected) {
            if (System.nanoTime() - deadline > 0) {
                throw AssertionError("timed out after " + timeout + " waiting for " + expected
                        + " push attempt(s); saw " + attempts.get())
            }
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("interrupted waiting for push attempts", e)
            }
        }
    }

    override fun sendToAll(fcmTokens: List<String>?, title: String?, body: String?,
                           data: Map<String, String>?, link: String?): BatchResult {
        // Counted before the failure check, so an attempt that throws is still observable: the
        // test that injects a total failure is exactly the one that needs awaitAttempts, and a
        // wait that only counted successes would hang on it forever.
        attempts.incrementAndGet()
        failure?.let { throw it }
        // !! where the Java dereferenced: a null list was an NPE there too.
        val tokens = fcmTokens!!
        sends.add(Sent(java.util.List.copyOf(tokens), title, body, if (data == null) java.util.Map.of() else java.util.Map.copyOf(data), link))
        val dead = tokens.stream().filter { deadTokens.contains(it) }.toList()
        val failed = tokens.stream().filter { failingTokens.contains(it) }.toList()
        val sent = tokens.size - dead.size - failed.size
        return BatchResult(sent, dead, failed)
    }
}
