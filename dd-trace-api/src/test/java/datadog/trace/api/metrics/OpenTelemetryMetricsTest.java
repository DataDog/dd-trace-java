package datadog.trace.api.metrics;

import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class OpenTelemetryMetricsTest {

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
}
