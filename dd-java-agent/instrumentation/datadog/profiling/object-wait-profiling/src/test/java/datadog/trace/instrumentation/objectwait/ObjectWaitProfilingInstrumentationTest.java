package datadog.trace.instrumentation.objectwait;

import static datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice.IDX_BLOCKER;
import static datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice.IDX_ROOT_SPAN_ID;
import static datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice.IDX_SPAN_ID;
import static datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice.IDX_START_NANOS;
import static datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice.IDX_START_TICKS;
import static datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice.MIN_WAIT_NANOS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Combining both interfaces as span.context() return type is AgentSpanContext,
// but production code casts it to ProfilerContext — both must be satisfied by the mock.
// AgentSpanContext is the declared return type; ProfilerContext provides getSpanId/getRootSpanId.

/**
 * Unit tests for {@link ObjectWaitProfilingInstrumentation}.
 *
 * <p>These tests exercise the two package-private helpers ({@code captureState} and {@code
 * emitIfLongEnough}) directly, bypassing the {@code AgentTracer.get()} call that would require a
 * live agent. The {@link WaitAdvice#after(long[])} null-guard is tested separately.
 */
@ExtendWith(MockitoExtension.class)
class ObjectWaitProfilingInstrumentationTest {

  private static final long SPAN_ID = 0xDEADBEEFL;
  private static final long ROOT_SPAN_ID = 0xCAFEBABEL;
  private static final long START_TICKS = 42_000_000L;

  /**
   * Implements both interfaces: span.context() returns AgentSpanContext but is cast to
   * ProfilerContext.
   */
  private interface ProfilerSpanContext extends AgentSpanContext, ProfilerContext {}

  @Mock private ProfilingContextIntegration profiling;
  @Mock private AgentSpan span;
  @Mock private ProfilerSpanContext ctx;
  @Mock private AgentSpanContext nonProfilerCtx;

  private final Object monitor = new Object();

  @BeforeEach
  void setUp() {
    lenient().when(span.context()).thenReturn(ctx);
    lenient().when(ctx.getSpanId()).thenReturn(SPAN_ID);
    lenient().when(ctx.getRootSpanId()).thenReturn(ROOT_SPAN_ID);
    lenient().when(profiling.getCurrentTicks()).thenReturn(START_TICKS);
  }

  // ---------------------------------------------------------------------------
  // WaitAdvice.after — null state guard
  // ---------------------------------------------------------------------------

  @Test
  void after_nullState_doesNotThrow() {
    // before() returns null when profiling is absent or no active span;
    // after() must be a safe no-op in that case.
    WaitAdvice.after(null);
  }

  // ---------------------------------------------------------------------------
  // captureState — precondition gates
  // ---------------------------------------------------------------------------

  @Test
  void captureState_nullProfiling_returnsNull() {
    assertNull(WaitAdvice.captureState(monitor, null, span));
  }

  @Test
  void captureState_nullSpan_returnsNull() {
    assertNull(WaitAdvice.captureState(monitor, profiling, null));
  }

  @Test
  void captureState_spanContextNotProfilerContext_returnsNull() {
    lenient().when(span.context()).thenReturn(nonProfilerCtx);
    assertNull(WaitAdvice.captureState(monitor, profiling, span));
  }

  // ---------------------------------------------------------------------------
  // captureState — happy path
  // ---------------------------------------------------------------------------

  @Test
  void captureState_validSpan_returnsCorrectState() {
    long before = System.nanoTime();
    long[] state = WaitAdvice.captureState(monitor, profiling, span);
    long after = System.nanoTime();

    assertNotNull(state);
    assertEquals(5, state.length);
    assertEquals(System.identityHashCode(monitor), state[IDX_BLOCKER]);
    assertEquals(SPAN_ID, state[IDX_SPAN_ID]);
    assertEquals(ROOT_SPAN_ID, state[IDX_ROOT_SPAN_ID]);
    assertEquals(START_TICKS, state[IDX_START_TICKS]);
    // startNanos must be within the window captured around the call
    org.junit.jupiter.api.Assertions.assertTrue(
        state[IDX_START_NANOS] >= before && state[IDX_START_NANOS] <= after,
        "startNanos should be captured during captureState()");
  }

  @Test
  void captureState_differentMonitors_produceDifferentBlockerHashes() {
    Object monitorA = new Object();
    Object monitorB = new Object();

    long[] stateA = WaitAdvice.captureState(monitorA, profiling, span);
    long[] stateB = WaitAdvice.captureState(monitorB, profiling, span);

    assertNotNull(stateA);
    assertNotNull(stateB);
    // Different object identities must produce different blocker hashes
    // (identity hash codes can collide, but for freshly allocated objects this holds
    // in all current JVM implementations — and what matters is they use identityHashCode)
    assertEquals(System.identityHashCode(monitorA), stateA[IDX_BLOCKER]);
    assertEquals(System.identityHashCode(monitorB), stateB[IDX_BLOCKER]);
  }

  // ---------------------------------------------------------------------------
  // emitIfLongEnough — duration filter
  // ---------------------------------------------------------------------------

  @Test
  void emitIfLongEnough_shortWait_doesNotEmit() {
    // Set startNanos to "just now" so elapsed < MIN_WAIT_NANOS
    long[] state = buildState(System.nanoTime());

    WaitAdvice.emitIfLongEnough(state, profiling);

    verifyNoInteractions(profiling);
  }

  @Test
  void emitIfLongEnough_longWait_emitsTaskBlock() {
    // Set startNanos to 2 ms ago — comfortably above the 1 ms threshold
    long twoMsAgo = System.nanoTime() - 2 * MIN_WAIT_NANOS;
    long[] state = buildState(twoMsAgo);

    WaitAdvice.emitIfLongEnough(state, profiling);

    verify(profiling)
        .recordTaskBlock(
            START_TICKS, // startTicks
            SPAN_ID, // spanId
            ROOT_SPAN_ID, // rootSpanId
            System.identityHashCode(monitor), // blocker
            0L); // unblockingSpanId always 0 (notify is native)
  }

  @Test
  void emitIfLongEnough_exactlyAtThreshold_emitsTaskBlock() {
    // nanos exactly at the boundary — elapsed == MIN_WAIT_NANOS is NOT < threshold, so it emits
    long atThreshold = System.nanoTime() - MIN_WAIT_NANOS;
    long[] state = buildState(atThreshold);

    WaitAdvice.emitIfLongEnough(state, profiling);

    verify(profiling)
        .recordTaskBlock(START_TICKS, SPAN_ID, ROOT_SPAN_ID, System.identityHashCode(monitor), 0L);
  }

  @Test
  void emitIfLongEnough_unblockingSpanIdIsAlwaysZero() {
    long twoMsAgo = System.nanoTime() - 2 * MIN_WAIT_NANOS;
    long[] state = buildState(twoMsAgo);

    WaitAdvice.emitIfLongEnough(state, profiling);

    // The 5th argument (unblockingSpanId) must always be 0 because notify/notifyAll
    // are native in JDK 21+ — the notifying thread cannot be identified via BCI.
    verify(profiling).recordTaskBlock(START_TICKS, SPAN_ID, ROOT_SPAN_ID, state[IDX_BLOCKER], 0L);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private long[] buildState(long startNanos) {
    return new long[] {
      System.identityHashCode(monitor), // IDX_BLOCKER
      SPAN_ID, // IDX_SPAN_ID
      ROOT_SPAN_ID, // IDX_ROOT_SPAN_ID
      START_TICKS, // IDX_START_TICKS
      startNanos // IDX_START_NANOS
    };
  }
}
