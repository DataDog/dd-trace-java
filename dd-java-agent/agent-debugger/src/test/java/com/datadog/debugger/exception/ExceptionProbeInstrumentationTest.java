package com.datadog.debugger.exception;

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
  public void noException() throws Exception {
    Config config = createConfig();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    TestSnapshotListener listener =
        setupExceptionDebugging(config, exceptionProbeManager, classNameFiltering);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    callMethodThrowingException(testClass); // instrument exception stacktrace
    assertEquals(2, exceptionProbeManager.getProbes().size());
    callMethodNoException(testClass);
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void basic() throws Exception {
    Config config = createConfig();
    ExceptionProbeManager exceptionProbeManager = new ExceptionProbeManager(classNameFiltering);
    TestSnapshotListener listener =
        setupExceptionDebugging(config, exceptionProbeManager, classNameFiltering);
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    callMethodThrowingException(testClass); // instrument exception stacktrace
    assertEquals(2, exceptionProbeManager.getProbes().size());
    callMethodThrowingException(testClass); // generate snapshots
    Map<String, String> probeIdByMethodName =
        exceptionProbeManager.getProbes().stream()
            .collect(
                Collectors.toMap(
                    exceptionProbe -> exceptionProbe.getWhere().getMethodName(),
                    ExceptionProbe::getId));
    assertEquals(2, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertEquals(probeIdByMethodName.get("processWithException"), snapshot0.getProbe().getId());
    assertEquals("oops", snapshot0.getCaptures().getReturn().getThrowable().getMessage());
    assertTrue(snapshot0.getCaptures().getReturn().getLocals().containsKey("@exception"));
    Snapshot snapshot1 = listener.snapshots.get(1);
    assertEquals(probeIdByMethodName.get("main"), snapshot1.getProbe().getId());
    assertEquals("oops", snapshot1.getCaptures().getReturn().getThrowable().getMessage());
    assertTrue(snapshot1.getCaptures().getReturn().getLocals().containsKey("@exception"));
  }

  private static void callMethodThrowingException(Class<?> testClass) {
    try {
      Reflect.on(testClass).call("main", "exception").get();
      Assertions.fail("should not reach this code");
    } catch (RuntimeException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
  }

  private static void callMethodNoException(Class<?> testClass) {
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
  }

  private TestSnapshotListener setupExceptionDebugging(
      Config config,
      ExceptionProbeManager exceptionProbeManager,
      ClassNameFiltering classNameFiltering) {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    ConfigurationUpdater configurationUpdater =
        new ConfigurationUpdater(
            instr,
            ExceptionProbeInstrumentationTest::createTransformer,
            config,
            new DebuggerSink(config, probeStatusSink),
            new ClassesToRetransformFinder(),
            exceptionProbeManager);
    TestSnapshotListener listener = new TestSnapshotListener(config, probeStatusSink);
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.initProbeResolver(configurationUpdater);
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());
    DebuggerContext.initExceptionDebugger(
        new DefaultExceptionDebugger(
            exceptionProbeManager, configurationUpdater, classNameFiltering));
    configurationUpdater.accept(null);
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

  private static DebuggerTransformer createTransformer(
      Config config,
      Configuration configuration,
      DebuggerTransformer.InstrumentationListener listener,
      DebuggerSink debuggerSink) {
    return new DebuggerTransformer(config, configuration, listener, debuggerSink);
  }
}
