package datadog.trace.observer.bootstrap;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ObserverRuntimeTest {
  @Test
  void defaultOffDoesNotSelectAnApplicationClasspathObserver() throws Exception {
    assertNull(datadog.gradle.plugin.observer.ObserverAgentSelection.attached());
    assertThrows(
        IllegalArgumentException.class,
        datadog.gradle.plugin.observer.ObserverAgentSelection::validate);
  }

  @Test
  void barePremainAndOrdinaryArgumentsNeedNoRoleOrArtifactMetadata() throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    try {
      System.clearProperty("tracing.observer.child.v1");
      for (String arguments : new String[] {null, "", "dd.service=ordinary"}) {
        try (URLClassLoader loader =
            new URLClassLoader(
                new URL[] {
                  ObserverRuntime.class.getProtectionDomain().getCodeSource().getLocation()
                },
                null)) {
          Class<?> runtime = loader.loadClass(ObserverRuntime.class.getName());
          runtime.getMethod("initializePremain", String.class).invoke(null, arguments);
          assertNull(runtime.getMethod("getProperty", String.class).invoke(null, "dd.writer.type"));
          assertNull(
              runtime
                  .getMethod("getProperty", String.class)
                  .invoke(null, "dd.civisibility.enabled"));
          assertTrue(
              ((Map<?, ?>) runtime.getMethod("propagationProperties").invoke(null)).isEmpty());
          assertThrows(
              java.lang.reflect.InvocationTargetException.class,
              () -> runtime.getMethod("initializePremain", String.class).invoke(null, arguments));
        }
      }
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void onlyEffectiveGeneratedFileLoggerSkipsDaemonStreamReset() throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    try {
      System.setProperty("tracing.observer.log.directory", "owned-logs");
      for (String destination :
          new String[] {"generated", "System.err", "System.out", "custom.log"}) {
        String key = "tracing.observer.config.datadog.slf4j.simpleLogger.logFile";
        System.clearProperty(key);
        if (!destination.equals("generated")) {
          System.setProperty(key, destination);
        }
        try (URLClassLoader loader =
            new URLClassLoader(
                new URL[] {
                  ObserverRuntime.class.getProtectionDomain().getCodeSource().getLocation()
                },
                null)) {
          Class<?> runtime = loader.loadClass(ObserverRuntime.class.getName());
          assertEquals(
              destination.equals("generated"),
              runtime.getMethod("usingGeneratedFileLogger").invoke(null));
          runtime
              .getMethod("setProperty", String.class, String.class)
              .invoke(null, "datadog.trace.observer.slf4j.simpleLogger.logFile", "System.err");
          assertEquals(false, runtime.getMethod("usingGeneratedFileLogger").invoke(null));
        }
      }
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void wholeCarrierRoundTripIsOpaqueAndLossless() {
    Map<String, String> values = new HashMap<>();
    values.put("traceparent", "00-1234-5678-01");
    values.put("tracestate", "dd=s:1");
    values.put("x-datadog-parent-id", "18446744073709551615");
    values.put("baggage", "space=\"quoted\",unicode=\u03b1\n${projectProperty}");
    List<Map<String, String>> maps =
        asList(values, singletonMap("DD_TAGS", "a:b c"), emptyMap(), values);
    String encoded = ObserverRuntime.encode(maps);
    assertFalse(encoded.contains("${"));
    assertEquals(maps, ObserverRuntime.decode(encoded));
    assertThrows(IllegalArgumentException.class, () -> ObserverRuntime.decode("invalid"));
    assertThrows(IllegalArgumentException.class, () -> ObserverRuntime.decode(encoded + "AA"));
  }

  @Test
  void independentSourceSnapshotsAndMutablePrivateOverlay() throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    try {
      Map<String, String> config = new HashMap<>();
      config.put("dd.service", "private-service");
      config.put("future.non.dd.key", "future-value");
      Map<String, String> carrier = new HashMap<>();
      carrier.put("traceparent", "private-carrier");
      carrier.put("dd.civisibility.build.instrumentation.enabled", "false");
      carrier.put("dd.civisibility.auto.configuration.enabled", "false");
      System.setProperty(
          "tracing.observer.child.v1",
          ObserverRuntime.encode(
              asList(config, singletonMap("DD_TAGS", "source:environment"), emptyMap(), carrier)));
      System.setProperty("dd.service", "subject-service");
      System.setProperty("traceparent", "subject-carrier");
      try (URLClassLoader loader =
          new URLClassLoader(
              new URL[] {ObserverRuntime.class.getProtectionDomain().getCodeSource().getLocation()},
              null)) {
        Class<?> runtime = loader.loadClass(ObserverRuntime.class.getName());
        assertEquals(
            "private-service",
            runtime.getMethod("getProperty", String.class).invoke(null, "dd.service"));
        assertEquals(
            "source:environment",
            runtime.getMethod("getenv", String.class).invoke(null, "DD_TAGS"));
        assertNull(runtime.getMethod("getProperty", String.class).invoke(null, "dd.tags"));
        System.setProperty("dd.service", "subject-mutated");
        assertEquals(
            "private-service",
            runtime.getMethod("getProperty", String.class).invoke(null, "dd.service"));
        Properties snapshot = (Properties) runtime.getMethod("getProperties").invoke(null);
        assertEquals("future-value", snapshot.getProperty("future.non.dd.key"));
        snapshot.setProperty("dd.service", "copy-mutated");
        assertEquals(
            "private-service",
            runtime.getMethod("getProperty", String.class).invoke(null, "dd.service"));
        assertEquals(carrier, runtime.getMethod("propagationProperties").invoke(null));
        assertEquals("subject-carrier", System.getProperty("traceparent"));
        assertNull(
            runtime
                .getMethod("setProperty", String.class, String.class)
                .invoke(null, "dd.civisibility.signal.server.port", "12345"));
        assertEquals(
            "12345",
            runtime
                .getMethod("getProperty", String.class)
                .invoke(null, "dd.civisibility.signal.server.port"));
        assertNull(System.getProperty("dd.civisibility.signal.server.port"));
        assertEquals(
            "12345",
            runtime
                .getMethod("clearProperty", String.class)
                .invoke(null, "dd.civisibility.signal.server.port"));
        assertNull(
            runtime
                .getMethod("getProperty", String.class)
                .invoke(null, "dd.civisibility.signal.server.port"));
      }
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void nestedOnlyTestFrameworksAreOffUnlessConfigured() throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    try {
      System.setProperty(
          "tracing.observer.child.v1",
          ObserverRuntime.encode(
              asList(
                  singletonMap("dd.integration.karate.enabled", "true"),
                  singletonMap("DD_TRACE_INTEGRATION_TESTNG_ENABLED", "true"),
                  emptyMap(),
                  emptyMap())));
      System.setProperty("dd.integration.junit-4.enabled", "true");
      try (URLClassLoader loader =
          new URLClassLoader(
              new URL[] {ObserverRuntime.class.getProtectionDomain().getCodeSource().getLocation()},
              null)) {
        Class<?> runtime = loader.loadClass(ObserverRuntime.class.getName());
        Method property = runtime.getMethod("getProperty", String.class);
        for (String name : new String[] {"junit-4", "scalatest", "weaver", "cucumber"}) {
          assertEquals("false", property.invoke(null, "dd.integration." + name + ".enabled"));
        }
        assertEquals("true", property.invoke(null, "dd.integration.karate.enabled"));
        assertNull(property.invoke(null, "dd.integration.testng.enabled"));
        assertEquals(
            "true",
            runtime
                .getMethod("getenv", String.class)
                .invoke(null, "DD_TRACE_INTEGRATION_TESTNG_ENABLED"));
        assertNull(property.invoke(null, "dd.integration.junit-5.enabled"));
        assertEquals("true", System.getProperty("dd.integration.junit-4.enabled"));
      }
    } finally {
      System.setProperties(original);
    }
  }

  @Test
  void fullNameNamespaceDoesNotWhitelistKeysOrRewriteValues() throws Exception {
    Properties original = (Properties) System.getProperties().clone();
    try {
      System.setProperty("tracing.observer.config.dd.future-key", "datadog.value=\u03b1");
      System.setProperty(
          "tracing.observer.config.datadog.slf4j.simpleLogger.defaultLogLevel", "WARN");
      try (URLClassLoader loader =
          new URLClassLoader(
              new URL[] {ObserverRuntime.class.getProtectionDomain().getCodeSource().getLocation()},
              null)) {
        Class<?> runtime = loader.loadClass(ObserverRuntime.class.getName());
        assertEquals(
            "datadog.value=\u03b1",
            runtime.getMethod("getProperty", String.class).invoke(null, "dd.future-key"));
        assertEquals(
            "WARN",
            runtime
                .getMethod("getProperty", String.class)
                .invoke(null, "datadog.trace.observer.slf4j.simpleLogger.defaultLogLevel"));
        assertNull(System.getProperty("dd.future-key"));
        assertTrue(((Map<?, ?>) runtime.getMethod("propagationProperties").invoke(null)).isEmpty());
      }
    } finally {
      System.setProperties(original);
    }
  }
}
