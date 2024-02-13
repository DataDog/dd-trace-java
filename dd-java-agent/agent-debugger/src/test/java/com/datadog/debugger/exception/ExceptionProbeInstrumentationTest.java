package com.datadog.debugger.exception;

import static com.datadog.debugger.agent.ConfigurationAcceptor.Source.REMOTE_CONFIG;
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
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.core.CoreTracer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ExceptionProbeInstrumentationTest {
  private final Instrumentation instr = ByteBuddyAgent.install();
  private ClassFileTransformer currentTransformer;
  private final ClassNameFiltering classNameFiltering =
      new ClassNameFiltering(
          Arrays.asList(
              "org.gradle.",
              "worker.org.gradle.",
              "org.junit.",
              "org.joor.",
              "com.datadog.debugger.exception."));;

  @BeforeEach
  public void before() {
    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
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
    callMethodThrowingRuntimeException(testClass); // instrument exception stacktrace
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
    callMethodThrowingRuntimeException(testClass); // instrument exception stacktrace
    assertEquals(2, exceptionProbeManager.getProbes().size());
    callMethodThrowingRuntimeException(testClass); // generate snapshots
    Map<String, Set<String>> probeIdsByMethodName =
        extractProbeIdsByMethodName(exceptionProbeManager);
    assertEquals(2, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertProbeId(probeIdsByMethodName, "processWithException", snapshot0.getProbe().getId());
    assertEquals("oops", snapshot0.getCaptures().getReturn().getCapturedThrowable().getMessage());
    assertTrue(snapshot0.getCaptures().getReturn().getLocals().containsKey("@exception"));
    Snapshot snapshot1 = listener.snapshots.get(1);
    assertProbeId(probeIdsByMethodName, "main", snapshot1.getProbe().getId());
    assertEquals("oops", snapshot1.getCaptures().getReturn().getCapturedThrowable().getMessage());
    assertTrue(snapshot1.getCaptures().getReturn().getLocals().containsKey("@exception"));
  }

  @Test
  public void differentExceptionsSameStack() throws Exception {
    Config config = createConfig();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    TestSnapshotListener listener =
        setupExceptionDebugging(config, exceptionProbeManager, classNameFiltering);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    callMethodThrowingRuntimeException(testClass); // instrument RuntimeException  stacktrace
    assertEquals(2, exceptionProbeManager.getProbes().size());
    callMethodThrowingIllegalArgException(
        testClass); // instrument IllegalArgumentException stacktrace
    assertEquals(3, exceptionProbeManager.getProbes().size());
    Map<String, Set<String>> probeIdsByMethodName =
        extractProbeIdsByMethodName(exceptionProbeManager);
    // snapshot  generated for main method when leaving it with last uncaught exception
    // and after registering the Illegal exception into ExceptionProbeManager
    assertEquals(1, listener.snapshots.size());
    listener.snapshots.clear();
    assertEquals(3, exceptionProbeManager.getProbes().size());
    assertEquals(2, exceptionProbeManager.instrumentedMethods().size());
    callMethodThrowingRuntimeException(testClass); // generate snapshots RuntimeException
    callMethodThrowingIllegalArgException(testClass); // generate snapshots IllegalArgumentException
    assertEquals(4, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertProbeId(probeIdsByMethodName, "processWithException", snapshot0.getProbe().getId());
    assertExceptionMsg("oops", snapshot0);
    Snapshot snapshot1 = listener.snapshots.get(1);
    assertProbeId(probeIdsByMethodName, "main", snapshot1.getProbe().getId());
    assertExceptionMsg("oops", snapshot1);
    Snapshot snapshot2 = listener.snapshots.get(2);
    assertProbeId(probeIdsByMethodName, "processWithException", snapshot2.getProbe().getId());
    assertExceptionMsg("illegal argument", snapshot2);
    Snapshot snapshot3 = listener.snapshots.get(3);
    assertProbeId(probeIdsByMethodName, "main", snapshot3.getProbe().getId());
    assertExceptionMsg("illegal argument", snapshot3);
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

  private static void callMethodThrowingRuntimeException(Class<?> testClass) {
    try {
      Reflect.on(testClass).call("main", "exception").get();
      Assertions.fail("should not reach this code");
    } catch (RuntimeException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
  }

  private static void callMethodThrowingIllegalArgException(Class<?> testClass) {
    try {
      Reflect.on(testClass).call("main", "illegal").get();
      Assertions.fail("should not reach this code");
    } catch (RuntimeException ex) {
      assertEquals("illegal argument", ex.getCause().getCause().getMessage());
    }
  }

  private static void callMethodNoException(Class<?> testClass) {
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
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
    configurationUpdater.setRetransformListener(exceptionDebugger);
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
