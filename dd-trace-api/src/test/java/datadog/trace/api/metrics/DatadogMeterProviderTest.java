package datadog.trace.api.metrics;

import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isPublic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class DatadogMeterProviderTest {

  @Test
  void exposesOnlyShutdownLifecycleMethod() throws Exception {
    Method shutdown = DatadogMeterProvider.class.getMethod("shutdown");

    assertTrue(isPublic(shutdown.getModifiers()));
    assertTrue(isAbstract(shutdown.getModifiers()));
    assertEquals(CompletableResultCode.class, shutdown.getReturnType());
    assertEquals(0, shutdown.getParameterCount());
    assertThrows(
        NoSuchMethodException.class, () -> DatadogMeterProvider.class.getMethod("forceFlush"));
  }
}
