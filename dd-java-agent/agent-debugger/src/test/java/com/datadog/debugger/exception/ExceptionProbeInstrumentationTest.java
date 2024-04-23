package com.datadog.debugger.exception;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
import static com.datadog.debugger.exception.DefaultExceptionDebugger.DD_DEBUG_ERROR_EXCEPTION_ID;
import static com.datadog.debugger.exception.DefaultExceptionDebugger.ERROR_DEBUG_INFO_CAPTURED;
import static com.datadog.debugger.exception.DefaultExceptionDebugger.SNAPSHOT_ID_TAG_FMT;
import static com.datadog.debugger.util.MoshiSnapshotTestHelper.getValue;
import static com.datadog.debugger.util.TestHelper.assertWithTimeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.agent.ClassesToRetransformFinder;
import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.agent.DebuggerTransformer;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.core.CoreTracer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

public class ExceptionProbeInstrumentationTest {
  private final Instrumentation instr = ByteBuddyAgent.install();
  private final TestTraceInterceptor traceInterceptor = new TestTraceInterceptor();
  private ClassFileTransformer currentTransformer;
  private final ClassNameFiltering classNameFiltering =
      new ClassNameFiltering(
          Stream.of(
                  "java.",
                  "jdk.",
                  "com.sun.",
                  "sun.",
                  "org.gradle.",
                  "worker.org.gradle.",
                  "org.junit.",
                  "org.joor.",
                  "com.datadog.debugger.exception.")
              .collect(Collectors.toSet()));

  @BeforeEach
  public void before() {
    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    tracer.addTraceInterceptor(traceInterceptor);
  }

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
  }

  @Test
  public void onlyInstrument() throws Exception {
    Config config = createConfig();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    TestSnapshotListener listener =
        setupExceptionDebugging(config, exceptionProbeManager, classNameFiltering);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    String fingerprint =
        callMethodThrowingRuntimeException(testClass); // instrument exception stacktrace
    assertWithTimeout(
        () -> exceptionProbeManager.isAlreadyInstrumented(fingerprint), Duration.ofSeconds(30));
    assertEquals(2, exceptionProbeManager.getProbes().size());
    callMethodNoException(testClass);
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void instrumentAndCaptureSnapshots() throws Exception {
    Config config = createConfig();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    TestSnapshotListener listener =
        setupExceptionDebugging(config, exceptionProbeManager, classNameFiltering);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    String fingerprint =
        callMethodThrowingRuntimeException(testClass); // instrument exception stacktrace
    assertWithTimeout(
        () -> exceptionProbeManager.isAlreadyInstrumented(fingerprint), Duration.ofSeconds(30));
    assertEquals(2, exceptionProbeManager.getProbes().size());
    callMethodThrowingRuntimeException(testClass); // generate snapshots
    Map<String, Set<String>> probeIdsByMethodName =
        extractProbeIdsByMethodName(exceptionProbeManager);
    assertEquals(1, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertProbeId(probeIdsByMethodName, "processWithException", snapshot0.getProbe().getId());
    assertEquals("oops", snapshot0.getCaptures().getReturn().getCapturedThrowable().getMessage());
    assertTrue(snapshot0.getCaptures().getReturn().getLocals().containsKey("@exception"));
    ProbeLocation location = snapshot0.getProbe().getLocation();
    assertEquals(
        location.getType() + "." + location.getMethod(), snapshot0.getStack().get(0).getFunction());
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals(snapshot0.getExceptionId(), span.getTags().get(DD_DEBUG_ERROR_EXCEPTION_ID));
    assertEquals(Boolean.TRUE, span.getTags().get(ERROR_DEBUG_INFO_CAPTURED));
    assertEquals(snapshot0.getId(), span.getTags().get(String.format(SNAPSHOT_ID_TAG_FMT, 0)));
  }

  @Test
  public void differentExceptionsSameStack() throws Exception {
    Config config = createConfig();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    TestSnapshotListener listener =
        setupExceptionDebugging(config, exceptionProbeManager, classNameFiltering);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    // instrument RuntimeException stacktrace
    String fingerprint0 = callMethodThrowingRuntimeException(testClass);
    assertWithTimeout(
        () -> exceptionProbeManager.isAlreadyInstrumented(fingerprint0), Duration.ofSeconds(30));
    assertEquals(2, exceptionProbeManager.getProbes().size());
    // instrument IllegalArgumentException stacktrace
    String fingerprint1 = callMethodThrowingIllegalArgException(testClass);
    assertWithTimeout(
        () -> exceptionProbeManager.isAlreadyInstrumented(fingerprint1), Duration.ofSeconds(30));
    assertEquals(4, exceptionProbeManager.getProbes().size());
    Map<String, Set<String>> probeIdsByMethodName =
        extractProbeIdsByMethodName(exceptionProbeManager);
    // clear traces from instrumenting calls
    traceInterceptor.getAllTraces().clear();
    // generate snapshots RuntimeException
    callMethodThrowingRuntimeException(testClass);
    // generate snapshots IllegalArgumentException
    callMethodThrowingIllegalArgException(testClass);
    assertEquals(2, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertProbeId(probeIdsByMethodName, "processWithException", snapshot0.getProbe().getId());
    assertExceptionMsg("oops", snapshot0);
    Snapshot snapshot1 = listener.snapshots.get(1);
    assertProbeId(probeIdsByMethodName, "processWithException", snapshot1.getProbe().getId());
    assertExceptionMsg("illegal argument", snapshot1);
    MutableSpan span0 = traceInterceptor.getAllTraces().get(0).get(0);
    assertEquals(snapshot0.getExceptionId(), span0.getTags().get(DD_DEBUG_ERROR_EXCEPTION_ID));
    assertEquals(Boolean.TRUE, span0.getTags().get(ERROR_DEBUG_INFO_CAPTURED));
    assertEquals(snapshot0.getId(), span0.getTags().get(String.format(SNAPSHOT_ID_TAG_FMT, 0)));
    MutableSpan span1 = traceInterceptor.getAllTraces().get(1).get(0);
    assertEquals(snapshot1.getExceptionId(), span1.getTags().get(DD_DEBUG_ERROR_EXCEPTION_ID));
    assertEquals(Boolean.TRUE, span1.getTags().get(ERROR_DEBUG_INFO_CAPTURED));
    assertEquals(snapshot1.getId(), span1.getTags().get(String.format(SNAPSHOT_ID_TAG_FMT, 0)));
  }

  @Test
  @DisabledIf(
      value = "datadog.trace.api.Platform#isJ9",
      disabledReason = "Bug in J9: no LocalVariableTable for ClassFileTransformer")
  public void recursive() throws Exception {
    Config config = createConfig();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    TestSnapshotListener listener =
        setupExceptionDebugging(config, exceptionProbeManager, classNameFiltering);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    // instrument RuntimeException stacktrace
    String fingerprint = callMethodFiboException(testClass);
    assertWithTimeout(
        () -> exceptionProbeManager.isAlreadyInstrumented(fingerprint), Duration.ofSeconds(30));
    assertEquals(11, exceptionProbeManager.getProbes().size());
    callMethodFiboException(testClass); // generate snapshots
    Map<String, Set<String>> probeIdsByMethodName =
        extractProbeIdsByMethodName(exceptionProbeManager);
    assertEquals(10, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertProbeId(probeIdsByMethodName, "fiboException", snapshot0.getProbe().getId());
    assertEquals(
        "oops fibo", snapshot0.getCaptures().getReturn().getCapturedThrowable().getMessage());
    assertTrue(snapshot0.getCaptures().getReturn().getLocals().containsKey("@exception"));
    assertEquals("1", getValue(snapshot0.getCaptures().getReturn().getArguments().get("n")));
    Snapshot snapshot1 = listener.snapshots.get(1);
    assertEquals("2", getValue(snapshot1.getCaptures().getReturn().getArguments().get("n")));
    Snapshot snapshot2 = listener.snapshots.get(2);
    assertEquals("3", getValue(snapshot2.getCaptures().getReturn().getArguments().get("n")));
    Snapshot snapshot9 = listener.snapshots.get(9);
    assertEquals("10", getValue(snapshot9.getCaptures().getReturn().getArguments().get("n")));
  }

  private static void assertExceptionMsg(String expectedMsg, Snapshot snapshot) {
    assertEquals(
        expectedMsg, snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
  }

  private static void assertProbeId(
      Map<String, Set<String>> probeIdsByMethodName, String methodName, String id) {
    assertTrue(probeIdsByMethodName.containsKey(methodName));
    assertTrue(probeIdsByMethodName.get(methodName).contains(id));
  }

  private String callMethodThrowingRuntimeException(Class<?> testClass) {
    try {
      Reflect.onClass(testClass).call("main", "exception").get();
      Assertions.fail("should not reach this code");
    } catch (RuntimeException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
      return Fingerprinter.fingerprint(ex, classNameFiltering);
    }
    return null;
  }

  private String callMethodThrowingIllegalArgException(Class<?> testClass) {
    try {
      Reflect.onClass(testClass).call("main", "illegal").get();
      Assertions.fail("should not reach this code");
    } catch (RuntimeException ex) {
      assertEquals("illegal argument", ex.getCause().getCause().getMessage());
      return Fingerprinter.fingerprint(ex, classNameFiltering);
    }
    return null;
  }

  private static void callMethodNoException(Class<?> testClass) {
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(84, result);
  }

  private String callMethodFiboException(Class<?> testClass) {
    try {
      Reflect.onClass(testClass).call("main", "recursive").get();
      Assertions.fail("should not reach this code");
    } catch (RuntimeException ex) {
      assertEquals("oops fibo", ex.getCause().getCause().getMessage());
      return Fingerprinter.fingerprint(ex, classNameFiltering);
    }
    return null;
  }

  private static Map<String, Set<String>> extractProbeIdsByMethodName(
      ExceptionProbeManager exceptionProbeManager) {
    return exceptionProbeManager.getProbes().stream()
        .collect(
            Collectors.groupingBy(
                exceptionProbe -> exceptionProbe.getWhere().getMethodName(),
                Collectors.mapping(ExceptionProbe::getId, Collectors.toSet())));
  }

  private TestSnapshotListener setupExceptionDebugging(
      Config config,
      ExceptionProbeManager exceptionProbeManager,
      ClassNameFiltering classNameFiltering) {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            instr,
            this::createTransformer,
            config,
            new DebuggerSink(config, probeStatusSink),
            new ClassesToRetransformFinder());
    TestSnapshotListener listener = new TestSnapshotListener(config, probeStatusSink);
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.initProbeResolver(configurationUpdater);
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());
    DefaultExceptionDebugger exceptionDebugger =
        new DefaultExceptionDebugger(
            exceptionProbeManager, configurationUpdater, classNameFiltering);
    DebuggerContext.initExceptionDebugger(exceptionDebugger);
    configurationUpdater.accept(REMOTE_CONFIG, null);
    return listener;
  }

  private static Config createConfig() {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    return config;
  }

  private DebuggerTransformer createTransformer(
      Config config,
      Configuration configuration,
      DebuggerTransformer.InstrumentationListener listener,
      DebuggerSink debuggerSink) {
    DebuggerTransformer debuggerTransformer =
        new DebuggerTransformer(config, configuration, listener, debuggerSink);
    currentTransformer = debuggerTransformer;
    return debuggerTransformer;
  }
}
