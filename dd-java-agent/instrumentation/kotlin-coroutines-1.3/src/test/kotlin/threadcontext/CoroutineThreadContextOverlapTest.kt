package datadog.trace.instrumentation.kotlin.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Demonstrates that a suspended coroutine can resume and finish on B before A starts restoring
 * the observed context element. Kotlin invokes every callback; the test only delays A's cleanup.
 *
 * Expected order: A:update -> A:body -> B:update -> B:body -> B:restore -> A:restore.
 * The coroutine bodies do not overlap: A has suspended before B resumes. Only cleanup overlaps.
 * This is a Kotlin contract test, not a reproduction of a Datadog scope or continuation leak.
 */
class CoroutineThreadContextOverlapTest {
  @Test
  fun nextResumeCanFinishBeforePreviousRestoreStarts() {
    val events = ConcurrentLinkedQueue<String>()
    val failure = AtomicReference<Throwable>()
    val continuation = AtomicReference<CancellableContinuation<Unit>>()
    val firstThread = AtomicReference<Thread>()
    // Distinct worker values let us check that each callback restores its own thread's state.
    val local = ThreadLocal<String>()
    val restoreBlocked = CountDownLatch(1)
    val releaseRestore = CountDownLatch(1)
    val firstRestored = CountDownLatch(1)
    val secondRestored = CountDownLatch(1)
    val blockOnce = AtomicBoolean()
    val first = Executors.newSingleThreadExecutor { task ->
      Thread({
        firstThread.set(Thread.currentThread())
        local.set("worker-A")
        task.run()
      }, "context-worker-A")
    }
    val second = Executors.newSingleThreadExecutor { task ->
      Thread({
        local.set("worker-B")
        task.run()
      }, "context-worker-B")
    }
    val dispatches = AtomicInteger()
    // Make the thread handoff deterministic instead of relying on a scheduler race.
    val dispatcher = object : CoroutineDispatcher() {
      override fun dispatch(context: CoroutineContext, block: Runnable) {
        (if (dispatches.getAndIncrement() == 0) first else second).execute(block)
      }
    }
    // "update" installs coroutine state before the body runs; its return value belongs to that
    // worker. "restore" puts that value back after the body suspends or completes.
    val observed = object : ThreadContextElement<String?>,
      AbstractCoroutineContextElement(object : CoroutineContext.Key<ThreadContextElement<String?>> {}) {
      override fun updateThreadContext(context: CoroutineContext): String? {
        events.add(if (Thread.currentThread() === firstThread.get()) "A:update" else "B:update")
        val previous = local.get()
        local.set("coroutine")
        return previous
      }

      override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        local.set(oldState)
        if (Thread.currentThread() === firstThread.get()) {
          events.add("A:restore")
          firstRestored.countDown()
        } else {
          events.add("B:restore")
          secondRestored.countDown()
        }
      }
    }

    // Pause in a separate element so A has not even entered the observed restore callback.
    // These latches force the ordering for the test; they are not a proposed tracer fix.
    fun restoreGate() = object : ThreadContextElement<Unit>,
      AbstractCoroutineContextElement(object : CoroutineContext.Key<ThreadContextElement<Unit>> {}) {
      override fun updateThreadContext(context: CoroutineContext) = Unit

      override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
        if (Thread.currentThread() === firstThread.get() && blockOnce.compareAndSet(false, true)) {
          events.add("A:cleanup-blocked")
          restoreBlocked.countDown()
          try {
            if (!releaseRestore.await(10, SECONDS)) {
              failure.compareAndSet(null, AssertionError("Timed out releasing A's cleanup"))
            }
          } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure.compareAndSet(null, error)
          }
        }
      }
    }
    val handler = CoroutineExceptionHandler { _, error -> failure.compareAndSet(null, error) }
    // 1.3 restores elements in forward order; newer versions use reverse order. A gate on each
    // side delays the observed restore in both cases, without depending on that implementation detail.
    val scope = CoroutineScope(dispatcher + handler + restoreGate() + observed + restoreGate())
    try {
      val job = scope.launch {
        assertEquals("coroutine", local.get())
        events.add("A:body")
        // Publish the continuation, then suspend. The test resumes it only after A reaches cleanup.
        suspendCancellableCoroutine<Unit> { continuation.set(it) }
        assertEquals("coroutine", local.get())
        events.add("B:body")
      }

      // This proves suspension has completed: Kotlin is already restoring A's other elements.
      assertTrue(restoreBlocked.await(10, SECONDS), "A never reached cleanup: $events")
      continuation.get().resume(Unit)
      // B must complete its body AND restore while A is still blocked. A passing test therefore
      // confirms the overlap is possible; it does not show that tracer propagation is correct.
      assertTrue(secondRestored.await(10, SECONDS), "B could not resume before A's restore: $events")
      assertTrue(job.isCompleted)
      assertEquals(1L, firstRestored.count, "A's observed restore must not have started")
      assertEquals(
        listOf("A:update", "A:body", "A:cleanup-blocked", "B:update", "B:body", "B:restore"),
        events.toList()
      )

      // Let A finish, then query each worker after its coroutine task has returned. Both must
      // retain their original values, not the coroutine value or the other worker's value.
      releaseRestore.countDown()
      assertTrue(firstRestored.await(10, SECONDS), "A did not finish restoring")
      assertEquals("worker-A", first.submit<String> { local.get() }.get(10, SECONDS))
      assertEquals("worker-B", second.submit<String> { local.get() }.get(10, SECONDS))
      assertEquals("A:restore", events.last())
      assertEquals(2, dispatches.get())
      assertNull(failure.get())
      println("Kotlin thread-context callback order: ${events.joinToString(" -> ")}")
    } finally {
      // Release the gate even on assertion failure so executor cleanup cannot strand A.
      releaseRestore.countDown()
      scope.cancel()
      first.shutdown()
      second.shutdown()
      if (!first.awaitTermination(10, SECONDS)) first.shutdownNow()
      if (!second.awaitTermination(10, SECONDS)) second.shutdownNow()
    }
  }
}
