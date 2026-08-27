package datadog.trace.api.metrics;

import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.internal.InternalTracer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenTelemetryMetricsTest {
  private final Tracer originalTracer = GlobalTracer.get();

  @AfterEach
  void restoreGlobalTracer() throws Exception {
    setGlobalTracer(originalTracer);
  }

  @Test
  void lifecycleIsUnavailableWithoutAnInstalledTracer() {
    CompletableFuture<Boolean> forceFlush = OpenTelemetryMetrics.forceFlush();
    CompletableFuture<Boolean> shutdown = OpenTelemetryMetrics.shutdown();

    assertTrue(forceFlush.isDone());
    assertFalse(forceFlush.join());
    assertTrue(shutdown.isDone());
    assertFalse(shutdown.join());
  }

  @Test
  void unavailableResultCannotBeChangedForLaterCalls() {
    CompletableFuture<Boolean> first = OpenTelemetryMetrics.forceFlush();

    first.obtrudeValue(true);

    assertFalse(OpenTelemetryMetrics.forceFlush().join());
  }

  @Test
  void delegatesLifecycleToInstalledInternalTracer() throws Exception {
    Tracer tracer = mock(Tracer.class, withSettings().extraInterfaces(InternalTracer.class));
    InternalTracer internalTracer = (InternalTracer) tracer;
    CompletableFuture<Boolean> forceFlush = CompletableFuture.completedFuture(true);
    CompletableFuture<Boolean> shutdown = CompletableFuture.completedFuture(false);
    when(internalTracer.forceFlushOtelMetrics()).thenReturn(forceFlush);
    when(internalTracer.shutdownOtelMetrics()).thenReturn(shutdown);
    setGlobalTracer(tracer);

    assertSame(forceFlush, OpenTelemetryMetrics.forceFlush());
    assertSame(shutdown, OpenTelemetryMetrics.shutdown());
  }

  @Test
  void internalTracerDefaultsReportLifecycleUnavailable() {
    InternalTracer tracer = mock(InternalTracer.class, CALLS_REAL_METHODS);

    assertFalse(tracer.forceFlushOtelMetrics().join());
    assertFalse(tracer.shutdownOtelMetrics().join());
  }

  @Test
  void exposesPublicStaticLifecycleMethods() throws Exception {
    assertLifecycleMethod("forceFlush");
    assertLifecycleMethod("shutdown");
  }

  private static void assertLifecycleMethod(String name) throws Exception {
    Method method = OpenTelemetryMetrics.class.getMethod(name);

    assertTrue(isPublic(method.getModifiers()));
    assertTrue(isStatic(method.getModifiers()));
    assertEquals(CompletableFuture.class, method.getReturnType());
    assertEquals(0, method.getParameterCount());
  }

  private static void setGlobalTracer(Tracer tracer) throws Exception {
    Field provider = GlobalTracer.class.getDeclaredField("provider");
    provider.setAccessible(true);
    provider.set(null, tracer);
  }
}
