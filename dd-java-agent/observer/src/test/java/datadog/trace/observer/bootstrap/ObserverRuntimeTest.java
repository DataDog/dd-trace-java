package datadog.trace.observer.bootstrap;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObserverRuntimeTest {
  private static final Set<String> ROOTS =
      new HashSet<>(
          asList("datadog/trace", "datadog/slf4j", "com/datadog/debugger", "net/bytebuddy"));

  private Properties original;

  @BeforeEach
  void saveProperties() {
    original = (Properties) System.getProperties().clone();
  }

  @AfterEach
  void restoreProperties() {
    System.setProperties(original);
  }

  /** A fresh runtime class with its own static state, plus the test package roots resource. */
  private static Class<?> isolatedRuntime() throws Exception {
    URL roots = ObserverRuntimeTest.class.getResource("/" + ObserverRuntime.ROOTS_RESOURCE);
    URL resources = Paths.get(roots.toURI()).getParent().toUri().toURL();
    URLClassLoader loader =
        new URLClassLoader(
            new URL[] {
              ObserverRuntime.class.getProtectionDomain().getCodeSource().getLocation(), resources
            },
            null);
    return loader.loadClass(ObserverRuntime.class.getName());
  }

  private static Object call(Class<?> runtime, String name, Object... arguments) throws Exception {
    for (Method method : runtime.getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
        return method.invoke(null, arguments);
      }
    }
    throw new NoSuchMethodException(name);
  }

  @Test
  void relocatesOnlyReferencesToKnownRoots() {
    String[][] relocated = {
      {"datadog.trace.api.Config", "datadog.trace.observer.trace.api.Config"},
      {"datadog/trace/api/Config", "datadog/trace/observer/trace/api/Config"},
      {"(ILdatadog/trace/api/Config;)V", "(ILdatadog/trace/observer/trace/api/Config;)V"},
      {"com.datadog.debugger.Probe", "datadog.trace.observer.com.datadog.debugger.Probe"},
      {"net.bytebuddy.ByteBuddy", "datadog.trace.observer.net.bytebuddy.ByteBuddy"},
      {"-Dnet.bytebuddy.dump=", "-Ddatadog.trace.observer.net.bytebuddy.dump="},
      {"META-INF/services/datadog.trace.X", "META-INF/services/datadog.trace.observer.trace.X"},
      {
        "datadog.slf4j.simpleLogger.log.datadog.trace.api.Config",
        "datadog.trace.observer.slf4j.simpleLogger.log.datadog.trace.observer.trace.api.Config"
      }
    };
    for (String[] pair : relocated) {
      assertEquals(pair[1], ObserverRuntime.relocate(pair[0], ROOTS), pair[0]);
    }
    for (String unchanged :
        asList(
            "java:comp/env/datadog/tags/",
            "/var/run/datadog/apm.socket",
            ".inject.datadog.attribute.enabled",
            "datadog.span.dispatch",
            "datadog.debugger",
            "datadog.",
            "datadog/compiler/annotations/SourcePath",
            "datadog.trace.observer.trace.api.Config",
            "__datadogContext$",
            "mydatadog.trace.api.Config",
            "org.junit.platform.engine.TestEngine")) {
      assertEquals(unchanged, ObserverRuntime.relocate(unchanged, ROOTS), unchanged);
    }
  }

  @Test
  void premainNeedsNoRoleOrArtifactMetadataAndRunsOnce() throws Exception {
    System.clearProperty(ObserverRuntime.CHILD);
    Class<?> runtime = isolatedRuntime();
    call(runtime, "initializePremain");
    assertNull(call(runtime, "getProperty", "dd.writer.type"));
    assertNull(call(runtime, "getProperty", "dd.civisibility.enabled"));
    assertTrue(((Map<?, ?>) call(runtime, "propagationProperties")).isEmpty());
    assertThrows(InvocationTargetException.class, () -> call(runtime, "initializePremain"));
  }

  @Test
  void onlyEffectiveGeneratedFileLoggerSkipsDaemonStreamReset() throws Exception {
    System.setProperty("tracing.observer.log.directory", "owned-logs");
    String key = "tracing.observer.config.datadog.slf4j.simpleLogger.logFile";
    for (String destination : new String[] {"generated", "System.err", "custom.log"}) {
      System.clearProperty(key);
      if (!destination.equals("generated")) {
        System.setProperty(key, destination);
      }
      Class<?> runtime = isolatedRuntime();
      assertEquals(destination.equals("generated"), call(runtime, "usingGeneratedFileLogger"));
      call(runtime, "setProperty", "datadog.trace.observer.slf4j.simpleLogger.logFile", "x");
      assertEquals(false, call(runtime, "usingGeneratedFileLogger"));
    }
  }

  @Test
  void envelopeRoundTripIsLosslessAndDeterministic() {
    Map<String, String> values = new HashMap<>();
    values.put("traceparent", "00-1234-5678-01");
    values.put("x-datadog-parent-id", "18446744073709551615");
    values.put("baggage", "space=\"quoted\",unicode=\u03b1\n${projectProperty}");
    ObserverRuntime.Envelope envelope =
        new ObserverRuntime.Envelope(values, singletonMap("DD_TAGS", "a:b c"), emptyMap(), values);
    String encoded = envelope.encode();
    assertFalse(encoded.contains("${"));
    assertEquals(
        encoded,
        new ObserverRuntime.Envelope(values, envelope.environment, emptyMap(), values).encode());
    ObserverRuntime.Envelope decoded = ObserverRuntime.Envelope.decode(encoded);
    assertEquals(envelope.config, decoded.config);
    assertEquals(envelope.environment, decoded.environment);
    assertEquals(envelope.carrier, decoded.carrier);
    assertTrue(decoded.launch.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> ObserverRuntime.Envelope.decode("invalid"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ObserverRuntime.Envelope(
                    singletonMap("k", "a\0b"), emptyMap(), emptyMap(), emptyMap())
                .encode());
  }

  @Test
  void workerReadsParentSnapshotWithPrivateMutableOverlay() throws Exception {
    Map<String, String> config = new HashMap<>();
    config.put("dd.service", "private-service");
    config.put("future.non.dd.key", "future-value");
    Map<String, String> carrier = new HashMap<>();
    carrier.put("traceparent", "private-carrier");
    carrier.put("dd.civisibility.build.instrumentation.enabled", "false");
    System.setProperty(
        ObserverRuntime.CHILD,
        new ObserverRuntime.Envelope(
                config, singletonMap("DD_TAGS", "source:environment"), emptyMap(), carrier)
            .encode());
    System.setProperty("dd.service", "subject-service");
    System.setProperty("traceparent", "subject-carrier");
    Class<?> runtime = isolatedRuntime();
    assertEquals("private-service", call(runtime, "getProperty", "dd.service"));
    assertEquals("source:environment", call(runtime, "getenv", "DD_TAGS"));
    assertNull(call(runtime, "getProperty", "dd.tags"));
    System.setProperty("dd.service", "subject-mutated");
    assertEquals("private-service", call(runtime, "getProperty", "dd.service"));
    Properties snapshot = (Properties) call(runtime, "getProperties");
    assertEquals("future-value", snapshot.getProperty("future.non.dd.key"));
    snapshot.setProperty("dd.service", "copy-mutated");
    assertEquals("private-service", call(runtime, "getProperty", "dd.service"));
    assertEquals(carrier, call(runtime, "propagationProperties"));
    assertEquals("subject-carrier", System.getProperty("traceparent"));
    assertNull(call(runtime, "setProperty", "dd.civisibility.signal.server.port", "12345"));
    assertEquals("12345", call(runtime, "getProperty", "dd.civisibility.signal.server.port"));
    assertNull(System.getProperty("dd.civisibility.signal.server.port"));
    assertEquals("12345", call(runtime, "clearProperty", "dd.civisibility.signal.server.port"));
  }

  @Test
  void fingerprintIsComputedOnDemandFromTheCallerConfiguration() throws Exception {
    System.setProperty("tracing.observer.config.dd.service", "first");
    Object first = call(isolatedRuntime(), "configurationFingerprint");
    assertEquals(first, call(isolatedRuntime(), "configurationFingerprint"));
    System.setProperty("tracing.observer.config.dd.service", "second");
    assertFalse(first.equals(call(isolatedRuntime(), "configurationFingerprint")));
  }

  @Test
  void localStableConfigCarriesTheObserverDefaults() {
    Map<String, Object> local = ObserverRuntime.stableConfig("LOCAL_STABLE_CONFIG");
    for (String name : asList("JUNIT_4", "TESTNG", "KARATE", "SCALATEST", "WEAVER", "CUCUMBER")) {
      assertEquals("false", local.get("DD_TRACE_" + name + "_ENABLED"), name);
    }
    assertEquals("datadog.*:com.datadog.*", local.get("DD_CIVISIBILITY_CODE_COVERAGE_INCLUDES"));
    assertEquals(7, local.size());
    assertTrue(ObserverRuntime.stableConfig("FLEET_STABLE_CONFIG").isEmpty());
  }

  @Test
  void fullNameNamespaceDoesNotWhitelistKeysOrRewriteValues() throws Exception {
    System.setProperty("tracing.observer.config.dd.future-key", "datadog.value=\u03b1");
    System.setProperty(
        "tracing.observer.config.datadog.slf4j.simpleLogger.defaultLogLevel", "WARN");
    Class<?> runtime = isolatedRuntime();
    assertEquals("datadog.value=\u03b1", call(runtime, "getProperty", "dd.future-key"));
    assertEquals(
        "WARN",
        call(runtime, "getProperty", "datadog.trace.observer.slf4j.simpleLogger.defaultLogLevel"));
    assertNull(System.getProperty("dd.future-key"));
  }

  @Test
  void loggerKeysRelocateClassSuffixesOnceWithoutRewritingValues() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("datadog.slf4j.simpleLogger.log.datadog.trace.api.Config", "datadog.v");
    properties.setProperty("datadog.slf4j.simpleLogger.log.net.bytebuddy.ByteBuddy", "datadog.v");
    Class<?> runtime = isolatedRuntime();
    Properties translated = (Properties) call(runtime, "loggerProperties", properties);
    assertEquals(
        "datadog.v",
        translated.getProperty(
            "datadog.trace.observer.slf4j.simpleLogger.log.datadog.trace.observer.trace.api.Config"));
    assertEquals(
        "datadog.v",
        translated.getProperty(
            "datadog.trace.observer.slf4j.simpleLogger.log.datadog.trace.observer.net.bytebuddy.ByteBuddy"));
    assertEquals(translated, call(runtime, "loggerProperties", translated));
  }

  @Test
  void onlyTheOuterGradleEngineLaunchOwnsObserverListeners() {
    StackTraceElement engine =
        new StackTraceElement(
            "org.junit.platform.launcher.core.EngineExecutionOrchestrator", "executeEngine", "", 1);
    StackTraceElement gradle =
        new StackTraceElement(
            "org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestDefinitionProcessor$CollectThenExecuteTestDefinitionConsumer",
            "processAllTestDefinitions",
            "",
            1);
    assertTrue(ObserverRuntime.isOuterGradleExecution(new StackTraceElement[] {engine, gradle}));
    assertFalse(
        ObserverRuntime.isOuterGradleExecution(new StackTraceElement[] {engine, engine, gradle}));
    // A nested launch on another thread has a single engine frame but no Gradle frame.
    assertFalse(ObserverRuntime.isOuterGradleExecution(new StackTraceElement[] {engine}));
    assertFalse(ObserverRuntime.isOuterGradleExecution(new StackTraceElement[] {gradle}));
    assertFalse(ObserverRuntime.isOuterGradleExecution(new StackTraceElement[0]));
  }
}
