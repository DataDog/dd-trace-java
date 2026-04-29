package opentelemetry14;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.junit.utils.config.WithConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for OpenTelemetry 1.4 instrumentation tests with:
 *
 * <ul>
 *   <li>Instrumentation enabled using config
 *   <li>OTel {@link Tracer} setup before each test
 *   <li>Context leak detection and storage cleanup after each test
 * </ul>
 */
@WithConfig(key = "integration.opentelemetry.experimental.enabled", value = "true")
public abstract class AbstractOpenTelemetry14Test extends AbstractInstrumentationTest {
  private static int tracerInstance;

  protected Tracer otelTracer;

  @BeforeEach
  void setupOtelTracer() {
    this.otelTracer =
        GlobalOpenTelemetry.get().getTracerProvider().get("test-tracer-" + tracerInstance++);
  }

  @AfterEach
  void checkOtelContextAndCleanup() {
    try {
      assertEquals(Context.current(), Context.root(), "OTel context leak detected");
    } finally {
      clearContextStorage();
    }
  }

  private static void clearContextStorage() {
    try {
      Class<?> storageClass = Class.forName("io.opentelemetry.context.ThreadLocalContextStorage");
      Field field = storageClass.getDeclaredField("THREAD_LOCAL_STORAGE");
      field.setAccessible(true);
      ((ThreadLocal<?>) field.get(null)).remove();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Failed to clear OTel context storage", e);
    }
  }
}
