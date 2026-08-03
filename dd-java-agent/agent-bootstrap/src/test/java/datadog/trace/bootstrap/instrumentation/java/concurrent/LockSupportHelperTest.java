// Copyright 2026 Datadog, Inc.
package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.WeakMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LockSupportHelperTest {
  private interface ProfilerSpanContext extends AgentSpanContext, ProfilerContext {}

  private AgentTracer.TracerAPI previousTracer;
  private final Set<Thread> targetThreads = new HashSet<>();

  @BeforeAll
  static void registerWeakMapSupplier() {
    WeakMap.Supplier.registerIfAbsent(
        new WeakMap.Supplier() {
          @Override
          protected <K, V> WeakMap<K, V> get() {
            return new TestWeakMap<>();
          }
        });
  }

  @BeforeEach
  void setUp() {
    previousTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracerWithActiveSpan(null));
    LockSupportHelper.UNPARKING_STATE.remove(Thread.currentThread());
  }

  @AfterEach
  void tearDown() {
    LockSupportHelper.UNPARKING_STATE.remove(Thread.currentThread());
    for (Thread target : targetThreads) {
      LockSupportHelper.UNPARKING_STATE.remove(target);
    }
    targetThreads.clear();
    AgentTracer.forceRegister(previousTracer);
  }

  @Test
  void nullIntegrationDoesNotAcceptEntry() {
    assertNull(LockSupportHelper.parkEnter(null));
  }

  @Test
  void rejectedEntryDoesNotRequireExit() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.parkEnter()).thenReturn(false);

    ProfilingContextIntegration accepted = LockSupportHelper.parkEnter(profiling);
    LockSupportHelper.parkExit(accepted, 0L, 17L);

    assertNull(accepted);
    verify(profiling).parkEnter();
    verify(profiling, never()).parkExit(0L, 17L);
  }

  @Test
  void acceptedEntryIsAlwaysPairedWithExit() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.parkEnter()).thenReturn(true);
    ProfilingContextIntegration accepted = LockSupportHelper.parkEnter(profiling);
    LockSupportHelper.parkExit(accepted, 19L, 23L);

    assertSame(profiling, accepted);
    verify(profiling).parkEnter();
    verify(profiling).parkExit(19L, 23L);
  }

  @Test
  void nullBlockerUsesZeroIdentity() {
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);
    when(profiling.parkEnter()).thenReturn(true);

    LockSupportHelper.parkExit(LockSupportHelper.parkEnter(profiling), 0L, 0L);

    verify(profiling).parkExit(0L, 0L);
  }

  @Test
  void entryAndExitFailuresDoNotEscapeInstrumentation() {
    ProfilingContextIntegration entryFailure = mock(ProfilingContextIntegration.class);
    doThrow(new IllegalStateException("enter")).when(entryFailure).parkEnter();
    assertNull(LockSupportHelper.parkEnter(entryFailure));

    ProfilingContextIntegration exitFailure = mock(ProfilingContextIntegration.class);
    when(exitFailure.parkEnter()).thenReturn(true);
    doThrow(new IllegalStateException("exit")).when(exitFailure).parkExit(0L, 0L);
    ProfilingContextIntegration accepted = LockSupportHelper.parkEnter(exitFailure);

    assertDoesNotThrow(() -> LockSupportHelper.parkExit(accepted, 0L, 0L));
  }

  @Test
  void acceptedExitDrainsUnparkAttribution() {
    Thread current = Thread.currentThread();
    LockSupportHelper.UnparkState state = new LockSupportHelper.UnparkState();
    state.unblockingSpanId = 31L;
    LockSupportHelper.UNPARKING_STATE.put(current, state);
    ProfilingContextIntegration profiling = mock(ProfilingContextIntegration.class);

    LockSupportHelper.parkExit(profiling, 0L);

    assertEquals(0L, unblockingSpanId(current));
    verify(profiling).parkExit(0L, 31L);
  }

  @Test
  void rejectedNestedExitDoesNotDrainOwnerAttribution() {
    Thread current = Thread.currentThread();
    LockSupportHelper.UnparkState state = new LockSupportHelper.UnparkState();
    state.unblockingSpanId = 32L;
    LockSupportHelper.UNPARKING_STATE.put(current, state);

    LockSupportHelper.parkExit(null, 0L);

    assertEquals(32L, unblockingSpanId(current));
  }

  @Test
  void latestTracedUnparkWins() {
    Thread target = newTargetThread();
    installActiveSpan(41L);
    LockSupportHelper.recordUnpark(target);
    installActiveSpan(42L);

    LockSupportHelper.recordUnpark(target);

    assertEquals(42L, unblockingSpanId(target));
  }

  @Test
  void laterUntracedUnparkClearsOlderTracedCaller() {
    Thread target = newTargetThread();
    installActiveSpan(51L);
    LockSupportHelper.recordUnpark(target);
    AgentTracer.forceRegister(tracerWithActiveSpan(null));

    LockSupportHelper.recordUnpark(target);

    assertEquals(0L, unblockingSpanId(target));
  }

  @Test
  void nonProfilerSpanContextClearsOlderTracedCaller() {
    Thread target = newTargetThread();
    installActiveSpan(61L);
    LockSupportHelper.recordUnpark(target);
    AgentSpan span = mock(AgentSpan.class);
    when(span.spanContext()).thenReturn(mock(AgentSpanContext.class));
    AgentTracer.forceRegister(tracerWithActiveSpan(span));

    LockSupportHelper.recordUnpark(target);

    assertEquals(0L, unblockingSpanId(target));
  }

  @Test
  void nullUnparkTargetIsNoOp() {
    installActiveSpan(71L);

    LockSupportHelper.recordUnpark(null);

    assertEquals(0, LockSupportHelper.UNPARKING_STATE.size());
  }

  private Thread newTargetThread() {
    Thread thread = new Thread(() -> {});
    targetThreads.add(thread);
    return thread;
  }

  private static long unblockingSpanId(Thread thread) {
    LockSupportHelper.UnparkState state = LockSupportHelper.UNPARKING_STATE.get(thread);
    return state == null ? 0L : state.unblockingSpanId;
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

  private static final class TestWeakMap<K, V> implements WeakMap<K, V> {
    private final ConcurrentMap<K, V> delegate = new ConcurrentHashMap<>();

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public boolean containsKey(K target) {
      return delegate.containsKey(target);
    }

    @Override
    public V get(K key) {
      return delegate.get(key);
    }

    @Override
    public void put(K key, V value) {
      delegate.put(key, value);
    }

    @Override
    public void putIfAbsent(K key, V value) {
      delegate.putIfAbsent(key, value);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> supplier) {
      return delegate.computeIfAbsent(key, supplier);
    }

    @Override
    public V remove(K key) {
      return delegate.remove(key);
    }
  }
}
