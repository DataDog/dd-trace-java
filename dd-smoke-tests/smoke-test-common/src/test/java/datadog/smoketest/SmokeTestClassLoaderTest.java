package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.context.Context;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class SmokeTestClassLoaderTest {
  @Test
  void testRunnerDoesNotInjectTracerOrLoggingClassesIntoBootstrap() {
    assertNotNull(Context.class.getClassLoader(), "Smoke tests run the tracer in child JVMs");
    assertNotNull(Logger.class.getClassLoader(), "Logging must remain isolated from child loaders");
  }
}
