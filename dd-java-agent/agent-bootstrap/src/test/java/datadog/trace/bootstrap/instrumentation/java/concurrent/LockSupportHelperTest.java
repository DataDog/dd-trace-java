// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LockSupportHelperTest {
  private interface ProfilerSpanContext extends AgentSpanContext, ProfilerContext {}

  private AgentTracer.TracerAPI previousTracer;

  @BeforeEach
  void setUp() {
    previousTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracerWithActiveSpan(null));
    LockSupportHelper.UNPARKING_SPAN.clear();
  }

  @AfterEach
  void tearDown() {
    LockSupportHelper.UNPARKING_SPAN.clear();
    AgentTracer.forceRegister(previousTracer);
  }

  @Test
  void nullIntegrationDoesNotAcceptEntry() {
    assertNull(LockSupportHelper.captureState(new Object(), null));
  }

  @Test
  void rejectedEntryDoesNotRequireExit() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.parkEnter()).thenReturn(false);

    LockSupportHelper.ParkState state = LockSupportHelper.captureState(new Object(), profiling);
    LockSupportHelper.finish(state, 17L);

    assertNull(state);
    verify(profiling).parkEnter();
    verify(profiling, never()).parkExit(0L, 17L);
  }

  @Test
  void acceptedEntryIsAlwaysPairedWithExit() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.parkEnter()).thenReturn(true);
    Object blocker = new Object();

    LockSupportHelper.ParkState state = LockSupportHelper.captureState(blocker, profiling);
    LockSupportHelper.finish(state, 23L);

    assertNotNull(state);
    verify(profiling).parkEnter();
    verify(profiling).parkExit(Integer.toUnsignedLong(System.identityHashCode(blocker)), 23L);
  }

  @Test
  void nullBlockerUsesZeroIdentity() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.parkEnter()).thenReturn(true);

    LockSupportHelper.finish(LockSupportHelper.captureState(null, profiling), 0L);

    verify(profiling).parkExit(0L, 0L);
  }

  @Test
  void entryAndExitFailuresDoNotEscapeInstrumentation() {
    ProfilingContextIntegration entryFailure = mock(ProfilingContextIntegration.class);
    doThrow(new IllegalStateException("enter")).when(entryFailure).parkEnter();
    assertNull(LockSupportHelper.captureState(null, entryFailure));

    ProfilingContextIntegration exitFailure = mock(ProfilingContextIntegration.class);
    when(exitFailure.parkEnter()).thenReturn(true);
    doThrow(new IllegalStateException("exit")).when(exitFailure).parkExit(0L, 0L);
    LockSupportHelper.ParkState state = LockSupportHelper.captureState(null, exitFailure);

    assertDoesNotThrow(() -> LockSupportHelper.finish(state, 0L));
  }

  @Test
  void finishAlwaysDrainsUnparkAttribution() {
    Thread current = Thread.currentThread();
    LockSupportHelper.UNPARKING_SPAN.put(current, 31L);

    LockSupportHelper.finish(null);

    assertNull(LockSupportHelper.UNPARKING_SPAN.get(current));
  }

  @Test
  void latestTracedUnparkWins() {
    Thread target = new Thread(() -> {});
    installActiveSpan(41L);
    LockSupportHelper.recordUnpark(target);
    installActiveSpan(42L);

    LockSupportHelper.recordUnpark(target);

    assertEquals(42L, LockSupportHelper.UNPARKING_SPAN.get(target));
  }

  @Test
  void laterUntracedUnparkClearsOlderTracedCaller() {
    Thread target = new Thread(() -> {});
    installActiveSpan(51L);
    LockSupportHelper.recordUnpark(target);
    AgentTracer.forceRegister(tracerWithActiveSpan(null));

    LockSupportHelper.recordUnpark(target);

    assertNull(LockSupportHelper.UNPARKING_SPAN.get(target));
  }

  @Test
  void nonProfilerSpanContextClearsOlderTracedCaller() {
    Thread target = new Thread(() -> {});
    installActiveSpan(61L);
    LockSupportHelper.recordUnpark(target);
    AgentSpan span = mock(AgentSpan.class);
    when(span.spanContext()).thenReturn(mock(AgentSpanContext.class));
    AgentTracer.forceRegister(tracerWithActiveSpan(span));

    LockSupportHelper.recordUnpark(target);

    assertNull(LockSupportHelper.UNPARKING_SPAN.get(target));
  }

  @Test
  void nullUnparkTargetIsNoOp() {
    installActiveSpan(71L);

    LockSupportHelper.recordUnpark(null);

    assertEquals(0, LockSupportHelper.UNPARKING_SPAN.size());
  }

  private static void installActiveSpan(long spanId) {
    AgentSpan span = mock(AgentSpan.class);
    ProfilerSpanContext context = mock(ProfilerSpanContext.class);
    when(span.spanContext()).thenReturn(context);
    when(context.getSpanId()).thenReturn(spanId);
    AgentTracer.forceRegister(tracerWithActiveSpan(span));
  }

  private static AgentTracer.TracerAPI tracerWithActiveSpan(AgentSpan span) {
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    return tracer;
  }
}
