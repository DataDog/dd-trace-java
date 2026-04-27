package datadog.trace.instrumentation.locksupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.instrumentation.locksupport.LockSupportProfilingInstrumentation.State;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LockSupportProfilingInstrumentation}.
 *
 * <p>These tests exercise the {@link State} map directly, verifying the mechanism used to
 * communicate the unblocking span ID from {@code UnparkAdvice} to {@code ParkAdvice}.
 */
class LockSupportProfilingInstrumentationTest {

  @BeforeEach
  void clearState() {
    State.UNPARKING_SPAN.clear();
  }

  @AfterEach
  void cleanupState() {
    State.UNPARKING_SPAN.clear();
  }

  // -------------------------------------------------------------------------
  // State map — basic contract
  // -------------------------------------------------------------------------

  @Test
  void state_put_and_remove() {
    Thread t = Thread.currentThread();
    long spanId = 12345L;

    State.UNPARKING_SPAN.put(t, spanId);
    Long retrieved = State.UNPARKING_SPAN.remove(t);

    assertNotNull(retrieved);
    assertEquals(spanId, (long) retrieved);
    // After removal the entry should be gone
    assertNull(State.UNPARKING_SPAN.get(t));
  }

  @Test
  void state_remove_returns_null_when_absent() {
    Thread t = new Thread(() -> {});
    assertNull(State.UNPARKING_SPAN.remove(t));
  }

  @Test
  void state_is_initially_empty() {
    assertTrue(State.UNPARKING_SPAN.isEmpty());
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
              Long unblockingId = State.UNPARKING_SPAN.remove(Thread.currentThread());
              capturedSpanId.set(unblockingId != null ? unblockingId : 0L);
            });

    parkedThread.start();
    ready.await(); // wait for parked thread to register itself

    // Simulate what UnparkAdvice.before does: record unparking span id
    State.UNPARKING_SPAN.put(parkedThread, unparkingSpanId);
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
              Long unblockingId = State.UNPARKING_SPAN.remove(Thread.currentThread());
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
    LockSupportProfilingInstrumentation.ParkAdvice.after(null);
    assertTrue(State.UNPARKING_SPAN.isEmpty());
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
    Thread t = Thread.currentThread();
    State.UNPARKING_SPAN.put(t, 99L);

    // Simulate park() returning with no active span (state == null)
    LockSupportProfilingInstrumentation.ParkAdvice.after(null);

    assertNull(
        State.UNPARKING_SPAN.get(t),
        "Stale UNPARKING_SPAN entry must be drained even when state is null");
  }
}
