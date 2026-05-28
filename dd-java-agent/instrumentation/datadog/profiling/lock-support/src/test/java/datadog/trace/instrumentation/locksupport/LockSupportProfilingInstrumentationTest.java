package datadog.trace.instrumentation.locksupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.java.concurrent.LockSupportHelper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LockSupportProfilingInstrumentation}.
 *
 * <p>These tests exercise the {@link LockSupportHelper} map directly, verifying the mechanism used
 * to communicate the unblocking span ID from {@code UnparkAdvice} to {@code ParkAdvice}.
 */
class LockSupportProfilingInstrumentationTest {

  @BeforeEach
  void clearState() {
    LockSupportHelper.UNPARKING_SPAN.clear();
  }

  @AfterEach
  void cleanupState() {
    LockSupportHelper.UNPARKING_SPAN.clear();
  }

  // -------------------------------------------------------------------------
  // State map — basic contract
  // -------------------------------------------------------------------------

  @Test
  void state_put_and_remove() {
    Thread current = Thread.currentThread();
    long spanId = 12345L;

    LockSupportHelper.UNPARKING_SPAN.put(current, spanId);
    Long retrieved = LockSupportHelper.UNPARKING_SPAN.remove(current);

    assertNotNull(retrieved);
    assertEquals(spanId, (long) retrieved);
    // After removal the entry should be gone
    assertNull(LockSupportHelper.UNPARKING_SPAN.get(current));
  }

  @Test
  void state_remove_returns_null_when_absent() {
    Thread t = new Thread(() -> {});
    assertNull(LockSupportHelper.UNPARKING_SPAN.remove(t));
  }

  @Test
  void state_is_initially_empty() {
    assertTrue(LockSupportHelper.UNPARKING_SPAN.isEmpty());
  }

  // -------------------------------------------------------------------------
  // Multithreaded: unpark thread populates map, parked thread reads it
  // -------------------------------------------------------------------------

  /**
   * Simulates the UnparkAdvice → ParkAdvice handoff:
   *
   * <ol>
   *   <li>Thread A (the "parked" thread) blocks on a latch.
   *   <li>Thread B (the "unparking" thread) places its span ID in {@code State.UNPARKING_SPAN} for
   *       Thread A and then releases the latch.
   *   <li>Thread A wakes up, reads and removes the span ID from the map.
   * </ol>
   */
  @Test
  void unparking_spanId_is_visible_to_parked_thread() throws InterruptedException {
    long unparkingSpanId = 99887766L;

    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    AtomicLong capturedSpanId = new AtomicLong(-1L);
    AtomicReference<Thread> parkedThreadRef = new AtomicReference<>();

    Thread parkedThread =
        new Thread(
            () -> {
              parkedThreadRef.set(Thread.currentThread());
              ready.countDown();
              try {
                go.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }

              // Simulate what ParkAdvice.after does: read and remove unblocking span id
              Long unblockingId = LockSupportHelper.UNPARKING_SPAN.remove(Thread.currentThread());
              capturedSpanId.set(unblockingId != null ? unblockingId : 0L);
            });

    parkedThread.start();
    ready.await(); // wait for parked thread to register itself

    // Simulate what UnparkAdvice.before does: record unparking span id
    LockSupportHelper.UNPARKING_SPAN.put(parkedThread, unparkingSpanId);
    go.countDown(); // unblock parked thread

    parkedThread.join(2_000);
    assertFalse(parkedThread.isAlive(), "Test thread did not finish in time");
    assertEquals(
        unparkingSpanId,
        capturedSpanId.get(),
        "Parked thread should have read the unblocking span id placed by unparking thread");
  }

  /**
   * Verifies that if no entry exists for the parked thread (i.e. the thread was unblocked by a
   * non-traced thread), the {@code remove} returns {@code null} and the code falls back to 0.
   */
  @Test
  void no_unparking_entry_yields_zero() throws InterruptedException {
    AtomicLong capturedSpanId = new AtomicLong(-1L);

    Thread parkedThread =
        new Thread(
            () -> {
              Long unblockingId = LockSupportHelper.UNPARKING_SPAN.remove(Thread.currentThread());
              capturedSpanId.set(unblockingId != null ? unblockingId : 0L);
            });
    parkedThread.start();
    parkedThread.join(2_000);

    assertEquals(
        0L, capturedSpanId.get(), "Should fall back to 0 when no unparking span id is recorded");
  }

  // -------------------------------------------------------------------------
  // ParkAdvice.after — null state is a no-op
  // -------------------------------------------------------------------------

  /**
   * When {@code ParkAdvice.before} returns {@code null} (profiler not active or no active span),
   * {@code ParkAdvice.after} must not throw and must not leave entries in {@code UNPARKING_SPAN}.
   * It does call {@code remove(currentThread)}, but on an empty map that is a no-op.
   */
  @Test
  void parkAdvice_after_null_state_isNoOp() {
    LockSupportHelper.finish(null);
    assertTrue(LockSupportHelper.UNPARKING_SPAN.isEmpty());
  }

  @Test
  void parkAdvice_captureState_nullProfiling_returnsNull() {
    assertNull(LockSupportHelper.captureState(new Object(), null));
  }

  @Test
  void parkAdvice_captureState_callsParkEnterAndRecordsBlocker() {
    // Span identity is no longer surfaced through ParkState — it is read natively from the OTEP
    // TLS sidecar inside ProfiledThread::parkEnter. The Java-side ParkState only needs to retain
    // the blocker hash so parkExit can pair the eventual TaskBlock with the right monitor.
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    Object blocker = new Object();

    LockSupportHelper.ParkState state = LockSupportHelper.captureState(blocker, profiling);

    assertNotNull(state);
    assertEquals(System.identityHashCode(blocker), state.blockerHash);
    verify(profiling).parkEnter();
  }

  @Test
  void parkAdvice_captureState_nullBlocker_recordsZeroHash() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);

    LockSupportHelper.ParkState state = LockSupportHelper.captureState(null, profiling);

    assertNotNull(state);
    assertEquals(0L, state.blockerHash);
    verify(profiling).parkEnter();
  }

  @Test
  void parkAdvice_finish_callsOriginalProfilingContext() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    LockSupportHelper.ParkState state = new LockSupportHelper.ParkState(profiling, 42L);

    LockSupportHelper.finish(state, 99L);

    verify(profiling).parkExit(42L, 99L);
  }

  @Test
  void parkAdvice_finish_nullState_doesNotTouchProfiling() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);

    LockSupportHelper.finish(null, 99L);

    verifyNoInteractions(profiling);
  }

  /**
   * Regression test for stale-entry misattribution.
   *
   * <p>If {@code unpark(t)} is called (inserting an entry into {@code UNPARKING_SPAN}) and thread
   * {@code t} then parks without an active span ({@code state == null}), the entry must still be
   * drained. Without the fix, it would linger and be incorrectly attributed to the next {@code
   * TaskBlock} emitted on that thread.
   */
  @Test
  void stale_entry_is_drained_when_park_fires_without_active_span() {
    Thread current = Thread.currentThread();
    LockSupportHelper.UNPARKING_SPAN.put(current, 99L);

    // Simulate park() returning with no active span (state == null)
    LockSupportHelper.finish(null);

    assertNull(
        LockSupportHelper.UNPARKING_SPAN.get(current),
        "Stale UNPARKING_SPAN entry must be drained even when state is null");
  }

  /**
   * If multiple unpark calls race for the same parked thread, the latest span ID should be consumed
   * and the entry must still be drained exactly once by ParkAdvice.after().
   */
  @Test
  void latest_unparking_span_wins_and_entry_is_drained() {
    Thread current = Thread.currentThread();
    LockSupportHelper.UNPARKING_SPAN.put(current, 101L);
    LockSupportHelper.UNPARKING_SPAN.put(current, 202L);

    Long consumed = LockSupportHelper.UNPARKING_SPAN.remove(current);
    assertNotNull(consumed);
    assertEquals(202L, consumed.longValue());
    assertNull(
        LockSupportHelper.UNPARKING_SPAN.get(current), "Entry must be removed after consumption");
  }
}
