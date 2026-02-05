package com.datadog.debugger.agent;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static com.datadog.debugger.util.MoshiSnapshotHelper.DEPTH_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELD_COUNT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.REDACTED_IDENT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.REDACTED_TYPE_REASON;
import static com.datadog.debugger.util.MoshiSnapshotTestHelper.VALUE_ADAPTER;
import static com.datadog.debugger.util.MoshiSnapshotTestHelper.deserializeCapturedValue;
import static datadog.trace.bootstrap.debugger.util.Redaction.REDACTED_VALUE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compile;
import static utils.InstrumentationTestHelper.compileAndLoadClass;
import static utils.InstrumentationTestHelper.getLineForLineProbe;
import static utils.InstrumentationTestHelper.loadClass;
import static utils.TestHelper.getFixtureContent;
import static utils.TestHelper.setFieldInConfig;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.datadog.debugger.util.TestSnapshotListener;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedContextProbe;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import groovy.lang.GroovyClassLoader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion;
import org.joor.Reflect;
import org.joor.ReflectException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import utils.SourceCompiler;

public class CapturedSnapshotTest extends CapturingTestBase {
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);
  private static final ProbeId PROBE_ID3 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId PROBE_ID4 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f9", 0);
  private static final ProbeId LINE_PROBE_ID1 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000001", 0);
  private static final ProbeId LINE_PROBE_ID2 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000002", 0);
  private static final ProbeId LINE_PROBE_ID3 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000003", 0);
  private static final ProbeId LINE_PROBE_ID4 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000004", 0);
  private static final String KOTLIN_EXT = ".kt";
  private static final String SCALA_EXT = ".scala";
  private static final String GROOVY_EXT = ".groovy";

  @Test
  public void bridgeMethods() throws IOException, URISyntaxException {
    final String CLASS_NAME = "BridgeMethods";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "process", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    String result = Reflect.onClass(testClass).call("main").get();
    assertEquals("hello world", result);
    assertEquals(1, listener.snapshots.size());
  }

  @Test
  public void methodNotFound() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "foobar", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    verify(probeStatusSink)
        .addError(eq(PROBE_ID), eq("Cannot find method CapturedSnapshot01::foobar"));
  }

  @Test
  public void methodProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    TestSnapshotListener listener =
        installMethodProbe(CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertNotNull(snapshot.getCaptures().getEntry());
    assertNotNull(snapshot.getCaptures().getReturn());
    assertCaptureArgs(snapshot.getCaptures().getEntry(), "arg", "java.lang.String", "1");
    assertCaptureArgs(snapshot.getCaptures().getReturn(), "arg", "java.lang.String", "1");
    assertTrue(snapshot.getDuration() > 0);
    assertTrue(snapshot.getStack().size() > 0);
    assertEquals("CapturedSnapshot01.main", snapshot.getStack().get(0).getFunction());
  }

  @Test
  public void methodProbeAtExit() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    TestSnapshotListener listener =
        installMethodProbeAtExit(CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    // no entry values capture
    assertEquals(CapturedContext.EMPTY_CAPTURING_CONTEXT, snapshot.getCaptures().getEntry());
    assertCaptureArgs(snapshot.getCaptures().getReturn(), "arg", "java.lang.String", "1");
    assertTrue(snapshot.getDuration() > 0);
    assertTrue(snapshot.getStack().size() > 0);
    assertEquals("CapturedSnapshot01.main", snapshot.getStack().get(0).getFunction());
  }

  @Test
  public void methodProbeAtExitWithCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "int (java.lang.String)")
            .when(
                new ProbeCondition(DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("1"))), "arg == '1'"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(CapturedContext.EMPTY_CAPTURING_CONTEXT, snapshot.getCaptures().getEntry());
    assertCaptureArgs(snapshot.getCaptures().getReturn(), "arg", "java.lang.String", "1");
    assertTrue(snapshot.getDuration() > 0);
    assertTrue(snapshot.getStack().size() > 0);
    assertEquals("CapturedSnapshot01.main", snapshot.getStack().get(0).getFunction());
  }

  @Test
  public void localVarHoistingNoPreviousStore() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.fasterxml.jackson.core.json.ByteSourceJsonBootstrapper";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "detectEncoding", null);
    Class<?> testClass =
        loadClass(
            CLASS_NAME,
            getClass().getResource("/classfiles/ByteSourceJsonBootstrapper.classfile").getFile());
    assertNotNull(testClass);
  }

  @Test
  public void singleLineProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener = installLineProbe(LINE_PROBE_ID1, CLASS_NAME, line);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(line), "arg", "java.lang.String", "1");
    assertCaptureLocals(snapshot.getCaptures().getLines().get(line), "var1", "int", "1");
    assertTrue(snapshot.getStack().size() > 0);
    assertEquals("CapturedSnapshot01.java", snapshot.getStack().get(0).getFileName());
  }

  @Test
  public void resolutionFails() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener = installLineProbe(LINE_PROBE_ID1, CLASS_NAME, line);
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.initProbeResolver(probeIndex -> null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void resolutionThrows() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    LogProbe lineProbe = createLineProbe(LINE_PROBE_ID1, CLASS_NAME, line);
    LogProbe methodProbe =
        createMethodProbe(PROBE_ID2, CLASS_NAME, "main", "int (java.lang.String)");
    TestSnapshotListener listener = installProbes(lineProbe, methodProbe);
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.initProbeResolver(
        probeIndex -> {
          throw new IllegalArgumentException("oops");
        });
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void constructor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "<init>", "(String, Object)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void overloadedConstructor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void veryOldClassFile() throws Exception {
    final String CLASS_NAME = "antlr.Token"; // compiled with jdk 1.2
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = Class.forName(CLASS_NAME);
    assertNotNull(testClass);
    testClass.newInstance();
    assertOneSnapshot(listener);
  }

  /**
   * Ensure older pre-Java 6 class files with JSR/RET can be rewritten without "JSR/RET are not
   * supported with computeFrames option" exceptions being thrown.
   */
  @Test
  public void veryOldClassFileWithJsrRet() throws Exception {
    final String CLASS_NAME = "antlr.Tool"; // compiled with jdk 1.2
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "copyFile", null);
    Class<?> testClass = Class.forName(CLASS_NAME);
    assertNotNull(testClass);
    try {
      Reflect.onClass(testClass).create().call("copyFile", null, null);
    } catch (Throwable t) {
      // ignore
    }
    assertOneSnapshot(listener);
  }

  @Test
  public void oldClass1_1() throws Exception {
    final String CLASS_NAME = "org.apache.commons.lang.BooleanUtils"; // compiled with jdk 1.1
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "toBoolean", null);
    Class<?> testClass =
        loadClass(
            CLASS_NAME, getClass().getResource("/classfiles/BooleanUtils.classfile").getFile());

    boolean result = Reflect.onClass(testClass).call("toBoolean", Boolean.TRUE).get();
    assertTrue(result);
    assertOneSnapshot(listener);
  }

  @Test
  public void oldJavacBug() throws Exception {
    setFieldInConfig(Config.get(), "dynamicInstrumentationHoistLocalVarsEnabled", true);
    try {
      final String CLASS_NAME = "com.datadog.debugger.classfiles.JavacBug"; // compiled with jdk 1.6
      TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "main", null);
      Class<?> testClass = Class.forName(CLASS_NAME);
      assertNotNull(testClass);
      int result = Reflect.onClass(testClass).call("main", "").get();
      assertEquals(45, result);
      // with local var hoisting and initialization at the beginning of the method, issue is
      // resolved
      assertEquals(1, listener.snapshots.size());
    } finally {
      setFieldInConfig(Config.get(), "dynamicInstrumentationHoistLocalVarsEnabled", false);
    }
  }

  @Test
  public void nestedConstructor() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot02";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "<init>", "(Throwable)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "init").get();
    assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void nestedConstructor2() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot13";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "<init>", "(int)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
  }

  @Test
  public void nestedConstructor3() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot14";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "<init>", "(int, int, int)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
  }

  @Test
  public void inheritedConstructor() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot06";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME + "$Inherited", "<init>", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "obj2", "java.lang.Object", (String) null);
    CapturedContext.CapturedValue obj2 =
        getFields(snapshot.getCaptures().getReturn().getArguments().get("this")).get("obj2");
    Map<String, CapturedContext.CapturedValue> obj2Fields =
        (Map<String, CapturedContext.CapturedValue>) obj2.getValue();
    assertEquals(24, obj2Fields.get("intValue").getValue());
    assertEquals(3.14, obj2Fields.get("doubleValue").getValue());
  }

  @Test
  public void largeStackInheritedConstructor() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot15";
    TestSnapshotListener listener =
        installProbes(
            createMethodProbe(PROBE_ID1, CLASS_NAME, "<init>", "()"),
            createMethodProbe(PROBE_ID2, CLASS_NAME, "<init>", "(String, long, String)"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    long result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(4_000_000_001L, result);
    assertSnapshots(listener, 2, PROBE_ID2, PROBE_ID1);
  }

  @Test
  public void multiMethods() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    TestSnapshotListener listener =
        installProbes(
            createMethodProbe(PROBE_ID1, CLASS_NAME, "f1", "(int)"),
            createMethodProbe(PROBE_ID2, CLASS_NAME, "f2", "(int)"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(48, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    Snapshot snapshot0 = snapshots.get(0);
    assertCaptureArgs(snapshot0.getCaptures().getEntry(), "value", "int", "31");
    assertCaptureReturnValue(snapshot0.getCaptures().getReturn(), "int", "31");
    Snapshot snapshot1 = snapshots.get(1);
    assertCaptureArgs(snapshot1.getCaptures().getEntry(), "value", "int", "17");
    assertCaptureReturnValue(snapshot1.getCaptures().getReturn(), "int", "17");
  }

  @Test
  public void multiProbeSameMethod() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    LogProbe probe = createMethodProbe(PROBE_ID1, CLASS_NAME, "f1", "(int)");
    LogProbe probe2 = createMethodProbe(PROBE_ID2, CLASS_NAME, "f1", "(int)");
    TestSnapshotListener listener = installProbes(probe, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(48, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    Snapshot snapshot0 = snapshots.get(0);
    assertCaptureArgs(snapshot0.getCaptures().getEntry(), "value", "int", "31");
    assertCaptureReturnValue(snapshot0.getCaptures().getReturn(), "int", "31");
    Snapshot snapshot1 = snapshots.get(1);
    assertCaptureArgs(snapshot1.getCaptures().getEntry(), "value", "int", "31");
    assertCaptureReturnValue(snapshot1.getCaptures().getReturn(), "int", "31");
  }

  private List<Snapshot> assertSnapshots(
      TestSnapshotListener listener, int expectedCount, ProbeId... probeIds) {
    assertEquals(expectedCount, listener.snapshots.size());
    for (int i = 0; i < probeIds.length; i++) {
      assertEquals(probeIds[i].getId(), listener.snapshots.get(i).getProbe().getId());
    }
    return listener.snapshots;
  }

  @Test
  public void catchBlock() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "f", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  @Disabled("no more support of line range")
  public void insideSynchronizedBlock() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    final int LINE_START = 46;
    final int LINE_END = 48;
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID)
            .where(CLASS_NAME, "synchronizedBlock", "(int)", LINE_START + "-" + LINE_END)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "synchronizedBlock").get();
    assertEquals(76, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 10);
    int count = 31;
    for (int i = 0; i < 10; i++) {
      Snapshot snapshot = snapshots.get(i);
      assertCaptureLocals(
          snapshot.getCaptures().getLines().get(LINE_START), "i", "int", String.valueOf(i));
      assertCaptureLocals(
          snapshot.getCaptures().getLines().get(LINE_START),
          "count",
          "int",
          String.valueOf(count += i));
    }
  }

  @Test
  @Disabled("no more support of line range")
  public void outsideSynchronizedBlock() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    final int LINE_START = 45;
    final int LINE_END = 49;
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID)
            .where(CLASS_NAME, "synchronizedBlock", "(int)", LINE_START + "-" + LINE_END)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "synchronizedBlock").get();
    assertEquals(76, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(2, snapshot.getCaptures().getLines().size());
    assertCaptureLocals(snapshot.getCaptures().getLines().get(LINE_START), "count", "int", "31");
    assertCaptureLocals(snapshot.getCaptures().getLines().get(LINE_END), "count", "int", "76");
  }

  @Test
  public void sourceFileProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID1, CLASS_NAME + ".java", line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(line), "value", "int", "31");
  }

  @Test
  public void simpleSourceFileProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID1, "CapturedSnapshot10.java", line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("main", snapshot.getProbe().getLocation().getMethod());
    assertCaptureLocals(snapshot.getCaptures().getLines().get(line), "var1", "int", "1");
  }

  @Test
  public void sourceFileProbeFullPath() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(
            createLineProbe(LINE_PROBE_ID1, "src/main/java/" + DIR_CLASS_NAME + ".java", line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("main", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(line), "arg", "java.lang.String", "2");
  }

  @Test
  public void sourceFileProbeFullPathTopLevelClass() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID2);
    TestSnapshotListener listener =
        installProbes(
            createLineProbe(LINE_PROBE_ID2, "src/main/java/" + DIR_CLASS_NAME + ".java", line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(42 * 42, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID2, listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(
        "com.datadog.debugger.TopLevel01", snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("process", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(line), "arg", "int", "42");
  }

  @Test
  public void methodProbeLineProbeMix() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot11";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(
            createLineProbe(LINE_PROBE_ID1, "src/main/java/" + DIR_CLASS_NAME + ".java", line),
            createMethodProbe(PROBE_ID2, CLASS_NAME, "main", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, LINE_PROBE_ID1, PROBE_ID2);
    Snapshot snapshot0 = snapshots.get(0);
    assertNull(snapshot0.getCaptures().getEntry());
    assertNull(snapshot0.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot0.getCaptures().getLines().size());
    Assertions.assertEquals(
        "com.datadog.debugger.CapturedSnapshot11", snapshot0.getProbe().getLocation().getType());
    assertEquals("main", snapshot0.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot0.getCaptures().getLines().get(line), "arg", "java.lang.String", "2");
    assertCaptureLocals(snapshot0.getCaptures().getLines().get(line), "var1", "int", "1");
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(
        "com.datadog.debugger.CapturedSnapshot11", snapshot1.getProbe().getLocation().getType());
    assertEquals("main", snapshot1.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot1.getCaptures().getEntry(), "arg", "java.lang.String", "2");
    assertCaptureReturnValue(snapshot1.getCaptures().getReturn(), "int", "2");
  }

  @Test
  public void sourceFileProbeScala() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot101";
    final String FILE_NAME = CLASS_NAME + SCALA_EXT;
    int line = getLineForLineProbe(CLASS_NAME, SCALA_EXT, LINE_PROBE_ID1);
    TestSnapshotListener listener = installProbes(createLineProbe(LINE_PROBE_ID1, FILE_NAME, line));
    String source = getFixtureContent("/" + FILE_NAME);
    Class<?> testClass = ScalaHelper.compileAndLoad(source, CLASS_NAME, FILE_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(line), "value", "int", "31");
  }

  @Test
  public void sourceFileProbeGroovy() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot201";
    int line = getLineForLineProbe(CLASS_NAME, GROOVY_EXT, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID1, CLASS_NAME + GROOVY_EXT, line));
    String source = getFixtureContent("/" + CLASS_NAME + GROOVY_EXT);
    GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
    Class<?> testClass = groovyClassLoader.parseClass(source);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    assertNull(snapshot.getCaptures().getEntry());
    assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(line), "value", "int", "31");
  }

  @Test
  @EnabledForJreRange(
      max = JRE.JAVA_25) // TODO: Fix for Java 26. Delete once Java 26 is officially released.
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Issue with J9 when compiling Kotlin code")
  public void sourceFileProbeKotlin() throws IOException, URISyntaxException {
    System.out.println(JavaVersion.class.getProtectionDomain().getCodeSource().getLocation());
    System.out.println("Java version:" + JavaVersion.current().feature);
    final String CLASS_NAME = "CapturedSnapshot301";
    int line = getLineForLineProbe(CLASS_NAME, KOTLIN_EXT, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID1, CLASS_NAME + KOTLIN_EXT, line));
    URL resource = CapturedSnapshotTest.class.getResource("/" + CLASS_NAME + KOTLIN_EXT);
    assertNotNull(resource);
    List<File> filesToDelete = new ArrayList<>();
    try {
      Class<?> testClass =
          KotlinHelper.compileAndLoad(CLASS_NAME, resource.getFile(), filesToDelete);
      Object companion = Reflect.onClass(testClass).get("Companion");
      int result = Reflect.on(companion).call("main", "").get();
      assertEquals(48, result);
      Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
      assertNull(snapshot.getCaptures().getEntry());
      assertNull(snapshot.getCaptures().getReturn());
      Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
      Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
      Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
      assertCaptureArgs(snapshot.getCaptures().getLines().get(line), "value", "int", "31");
    } finally {
      filesToDelete.forEach(File::delete);
    }
  }

  @Test
  @EnabledForJreRange(
      max = JRE.JAVA_25) // TODO: Fix for Java 26. Delete once Java 26 is officially released.
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Issue with J9 when compiling Kotlin code")
  public void suspendKotlin() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot302";
    int line = getLineForLineProbe(CLASS_NAME, ".kt", LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID1, CLASS_NAME + ".kt", line));
    URL resource = CapturedSnapshotTest.class.getResource("/" + CLASS_NAME + ".kt");
    assertNotNull(resource);
    List<File> filesToDelete = new ArrayList<>();
    try {
      Class<?> testClass =
          KotlinHelper.compileAndLoad(CLASS_NAME, resource.getFile(), filesToDelete);
      Object companion = Reflect.onClass(testClass).get("Companion");
      int result = Reflect.on(companion).call("main", "").get();
      assertEquals(0, result);
      Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
      assertCaptureFields(snapshot.getCaptures().getLines().get(line), "intField", "int", "42");
      assertCaptureFields(
          snapshot.getCaptures().getLines().get(9), "strField", String.class.getTypeName(), "foo");
    } finally {
      filesToDelete.forEach(File::delete);
    }
  }

  @Test
  @EnabledForJreRange(
      max = JRE.JAVA_25) // TODO: Fix for Java 26. Delete once Java 26 is officially released.
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Issue with J9 when compiling Kotlin code")
  public void suspendMethodKotlin() {
    final String CLASS_NAME = "CapturedSnapshot302";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "download", null));
    URL resource = CapturedSnapshotTest.class.getResource("/" + CLASS_NAME + ".kt");
    assertNotNull(resource);
    List<File> filesToDelete = new ArrayList<>();
    try {
      Class<?> testClass =
          KotlinHelper.compileAndLoad(CLASS_NAME, resource.getFile(), filesToDelete);
      Object companion = Reflect.onClass(testClass).get("Companion");
      int result = Reflect.on(companion).call("main", "1").get();
      assertEquals(1, result);
      // 2 snapshots are expected because the method is executed twice one for each state
      // before the delay, after the delay
      List<Snapshot> snapshots = assertSnapshots(listener, 2);
      Snapshot snapshot0 = snapshots.get(0);
      assertCaptureReturnValue(
          snapshot0.getCaptures().getReturn(),
          "kotlin.coroutines.intrinsics.CoroutineSingletons",
          "COROUTINE_SUSPENDED");
      Snapshot snapshot1 = snapshots.get(1);
      assertCaptureReturnValue(
          snapshot1.getCaptures().getReturn(), String.class.getTypeName(), "1");
    } finally {
      filesToDelete.forEach(File::delete);
    }
  }

  @Test
  @EnabledForJreRange(
      max = JRE.JAVA_25) // TODO: Fix for Java 26. Delete once Java 26 is officially released.
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Issue with J9 when compiling Kotlin code")
  public void hoistVarKotlin() {
    final String CLASS_NAME = "CapturedSnapshot303";
    TestSnapshotListener listener =
        installProbes(createMethodProbeAtExit(PROBE_ID, CLASS_NAME + "$Companion", "main", null));
    URL resource = CapturedSnapshotTest.class.getResource("/" + CLASS_NAME + ".kt");
    assertNotNull(resource);
    List<File> filesToDelete = new ArrayList<>();
    try {
      Class<?> testClass =
          KotlinHelper.compileAndLoad(CLASS_NAME, resource.getFile(), filesToDelete);
      Object companion = Reflect.onClass(testClass).get("Companion");
      int result = Reflect.on(companion).call("main", "").get();
      assertEquals(0, result);
      Snapshot snapshot = assertOneSnapshot(listener);
    } finally {
      filesToDelete.forEach(File::delete);
    }
  }

  @Test
  public void fieldExtractor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID1, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(1, 100, 255, Limits.DEFAULT_FIELD_COUNT).build();
    builder = createProbeBuilder(PROBE_ID2, CLASS_NAME, "createCompositeData", "()");
    LogProbe compositeDataProbe = builder.capture(1, 3, 255, Limits.DEFAULT_FIELD_COUNT).build();
    TestSnapshotListener listener = installProbes(simpleDataProbe, compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    Snapshot simpleSnapshot = snapshots.get(0);
    Map<String, String> expectedSimpleFields = new HashMap<>();
    expectedSimpleFields.put("intValue", "42");
    expectedSimpleFields.put("strValue", "foo");
    assertCaptureReturnValue(
        simpleSnapshot.getCaptures().getReturn(),
        "CapturedSnapshot04$SimpleData",
        expectedSimpleFields);
    Snapshot compositeSnapshot = snapshots.get(1);
    Map<String, String> expectedCompositeFields = new HashMap<>();
    expectedCompositeFields.put("nullsd", "null");
    expectedCompositeFields.put("l1", DEPTH_REASON); // notCapturedReason
    expectedCompositeFields.put("s1", DEPTH_REASON); // notCapturedReason
    expectedCompositeFields.put("s2", DEPTH_REASON); // notCapturedReason
    assertCaptureReturnValue(
        compositeSnapshot.getCaptures().getReturn(),
        "CapturedSnapshot04$CompositeData",
        expectedCompositeFields);
  }

  @Test
  public void fieldExtractorDeep2() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createCompositeData", "()");
    LogProbe compositeDataProbe = builder.capture(2, 3, 255, Limits.DEFAULT_FIELD_COUNT).build();
    TestSnapshotListener listener = installProbes(compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue returnValue =
        snapshot.getCaptures().getReturn().getLocals().get("@return");
    Map<String, CapturedContext.CapturedValue> fields = getFields(returnValue);
    assertTrue(fields.containsKey("nullsd"));
    assertTrue(fields.containsKey("l1"));
    CapturedContext.CapturedValue s1 = fields.get("s1");
    Map<String, CapturedContext.CapturedValue> s1Fields =
        (Map<String, CapturedContext.CapturedValue>) s1.getValue();
    assertEquals("101", String.valueOf(s1Fields.get("intValue").getValue()));
    assertEquals("foo1", s1Fields.get("strValue").getValue());
    assertEquals("null", String.valueOf(s1Fields.get("listValue").getValue()));
    assertEquals(DEPTH_REASON, String.valueOf(s1Fields.get("listValue").getNotCapturedReason()));
  }

  @Test
  public void fieldExtractorLength() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(1, 100, 2, Limits.DEFAULT_FIELD_COUNT).build();
    TestSnapshotListener listener = installProbes(simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, String> expectedFields = new HashMap<>();
    expectedFields.put("intValue", "42");
    expectedFields.put("strValue", "truncated");
    expectedFields.put("listValue", DEPTH_REASON);
    assertCaptureReturnValue(
        snapshot.getCaptures().getReturn(), "CapturedSnapshot04$SimpleData", expectedFields);
  }

  @Test
  public void fieldExtractorDisabled() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(0, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    TestSnapshotListener listener = installProbes(simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, CapturedContext.CapturedValue> fields = getFields(simpleData);
    assertEquals(1, fields.size());
    assertEquals(DEPTH_REASON, fields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
  }

  @Test
  public void fieldExtractorDepth0() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(0, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    TestSnapshotListener listener = installProbes(simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, CapturedContext.CapturedValue> simpleDataFields = getFields(simpleData);
    assertEquals(1, simpleDataFields.size());
    assertEquals(
        DEPTH_REASON, simpleDataFields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
  }

  @Test
  public void fieldExtractorDepth1() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(1, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    TestSnapshotListener listener = installProbes(simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, CapturedContext.CapturedValue> simpleDataFields = getFields(simpleData);
    assertEquals(4, simpleDataFields.size());
    assertEquals("foo", simpleDataFields.get("strValue").getValue());
    assertEquals(42, simpleDataFields.get("intValue").getValue());
    assertEquals(DEPTH_REASON, simpleDataFields.get("listValue").getNotCapturedReason());
  }

  @Test
  public void fieldExtractorDuplicateUnionDepth() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe probe1 = builder.capture(0, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    LogProbe probe2 = builder.capture(3, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    TestSnapshotListener listener = installProbes(probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2);
    CapturedContext.CapturedValue simpleData =
        snapshots.get(0).getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, CapturedContext.CapturedValue> simpleDataFields = getFields(simpleData);
    assertEquals(4, simpleDataFields.size());
    assertEquals("foo", simpleDataFields.get("strValue").getValue());
    assertEquals(42, simpleDataFields.get("intValue").getValue());
  }

  @Test
  public void fieldExtractorCount2() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createCompositeData", "()");
    LogProbe compositeDataProbe = builder.capture(2, 3, 255, 2).build();
    TestSnapshotListener listener = installProbes(compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue returnValue =
        snapshot.getCaptures().getReturn().getLocals().get("@return");
    assertEquals("CapturedSnapshot04$CompositeData", returnValue.getType());
    Map<String, CapturedContext.CapturedValue> fields = getFields(returnValue);
    assertEquals(3, fields.size());
    assertEquals(FIELD_COUNT_REASON, fields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
    Map<String, CapturedContext.CapturedValue> s1Fields =
        (Map<String, CapturedContext.CapturedValue>) fields.get("s1").getValue();
    assertEquals("foo1", s1Fields.get("strValue").getValue());
    assertEquals(101, s1Fields.get("intValue").getValue());
    Map<String, CapturedContext.CapturedValue> s2Fields =
        (Map<String, CapturedContext.CapturedValue>) fields.get("s2").getValue();
    assertEquals("foo2", s2Fields.get("strValue").getValue());
    assertEquals(202, s2Fields.get("intValue").getValue());

    CapturedContext.CapturedValue compositeData =
        snapshot.getCaptures().getReturn().getLocals().get("compositeData");
    Map<String, CapturedContext.CapturedValue> compositeDataFields = getFields(compositeData);
    assertEquals(3, compositeDataFields.size());
    assertEquals(
        FIELD_COUNT_REASON,
        compositeDataFields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
    assertTrue(compositeDataFields.containsKey("s1"));
    assertTrue(compositeDataFields.containsKey("s2"));
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_17)
  public void fieldExtractorNotAccessible() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot30";
    LogProbe logProbe =
        createMethodProbe(PROBE_ID, CLASS_NAME + "$MyObjectInputStream", "process", "()");
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureFieldsNotCaptured(
        snapshot.getCaptures().getReturn(),
        "bin",
        "Field is not accessible: module java.base does not opens/exports to the current module");
    assertCaptureFieldsNotCaptured(
        snapshot.getCaptures().getReturn(),
        "vlist",
        "Field is not accessible: module java.base does not opens/exports to the current module");
  }

  @Test
  public void uncaughtException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "triggerUncaughtException", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.onClass(testClass).call("main", "triggerUncaughtException").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureThrowable(
        snapshot.getCaptures().getReturn(),
        "CapturedSnapshot05$CustomException",
        "oops",
        "CapturedSnapshot05.triggerUncaughtException",
        8);
    Map<String, String> expectedFields = new HashMap<>();
    expectedFields.put("detailMessage", "oops");
    expectedFields.put("additionalMsg", "I did it again");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(),
        "@exception",
        "CapturedSnapshot05$CustomException",
        expectedFields);
  }

  @Test
  public void uncaughtExceptionCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    final String LOG_TEMPLATE = "exception msg={@exception.detailMessage}";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "triggerUncaughtException", "()")
            .evaluateAt(MethodLocation.EXIT)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.and(
                            DSL.instanceOf(
                                DSL.ref("@exception"),
                                DSL.value("CapturedSnapshot05$CustomException")),
                            DSL.eq(
                                DSL.getMember(DSL.ref("@exception"), "detailMessage"),
                                DSL.value("oops")),
                            DSL.eq(
                                DSL.getMember(DSL.ref("@exception"), "additionalMsg"),
                                DSL.value("I did it again")))),
                    "@exception instanceof \"CapturedSnapshot05$CustomException\" and @exception.detailMessage == 'oops' and @exception.additionalMsg == 'I did it again'"))
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.onClass(testClass).call("main", "triggerUncaughtException").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals("exception msg=oops", snapshot.getMessage());
    assertCaptureThrowable(
        snapshot.getCaptures().getReturn(),
        "CapturedSnapshot05$CustomException",
        "oops",
        "CapturedSnapshot05.triggerUncaughtException",
        8);
  }

  @Test
  public void caughtException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "triggerCaughtException", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "triggerCaughtException").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureThrowable(
        snapshot.getCaptures().getCaughtExceptions().get(0),
        "java.lang.IllegalStateException",
        "oops",
        "CapturedSnapshot05.triggerCaughtException",
        13);
  }

  @Test
  public void lineEmptyCaughtException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    int line1 = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    int line2 = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID2);
    int line3 = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID3);
    LogProbe probe1 = createLineProbe(LINE_PROBE_ID1, CLASS_NAME, line1);
    LogProbe probe2 = createLineProbe(LINE_PROBE_ID2, CLASS_NAME, line2);
    LogProbe probe3 = createLineProbe(LINE_PROBE_ID3, CLASS_NAME, line3);
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "triggerSwallowedException").get();
    assertEquals(-1, result);
    List<Snapshot> snapshots =
        assertSnapshots(listener, 3, LINE_PROBE_ID1, LINE_PROBE_ID2, LINE_PROBE_ID3);
    Snapshot snapshot0 = snapshots.get(0);
    Map<String, String> expectedFields0 = new HashMap<>();
    expectedFields0.put("detailMessage", "oops");
    assertCaptureLocals(
        snapshot0.getCaptures().getLines().get(line1),
        "ex0",
        IllegalStateException.class.getTypeName(),
        expectedFields0);
    Snapshot snapshot1 = snapshots.get(1);
    Map<String, String> expectedFields1 = new HashMap<>();
    expectedFields1.put("detailMessage", "nope!");
    assertCaptureLocals(
        snapshot1.getCaptures().getLines().get(line2),
        "ex",
        IllegalArgumentException.class.getTypeName(),
        expectedFields1);
    Snapshot snapshot2 = snapshots.get(2);
    Map<String, String> expectedFields2 = new HashMap<>();
    expectedFields2.put("detailMessage", "not there");
    assertCaptureLocals(
        snapshot2.getCaptures().getLines().get(line3),
        "ex",
        FileNotFoundException.class.getTypeName(),
        expectedFields2);
  }

  @Test
  public void noUncaughtExceptionCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    final String LOG_TEMPLATE = "exception?: {isDefined(@exception)}";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "int (String)")
            .evaluateAt(MethodLocation.EXIT)
            .when(
                new ProbeCondition(
                    DSL.when(DSL.not(DSL.isDefined(DSL.ref("@exception")))),
                    "not(isDefined(@exception))"))
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(listener);
  }

  @Test
  public void rateLimitSnapshot() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    LogProbe logProbes =
        new LogProbe.Builder()
            .language(LANGUAGE)
            .probeId(LINE_PROBE_ID1)
            .where(CLASS_NAME, line)
            .sampling(new LogProbe.Sampling(1))
            .build();
    TestSnapshotListener listener = installProbes(logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.onClass(testClass).call("main", "1").get();
      assertEquals(3, result);
    }
    assertTrue(listener.snapshots.size() < 20);
  }

  @Test
  public void globalRateLimitSnapshot() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    LogProbe probe1 = createProbeBuilder(PROBE_ID1, CLASS_NAME, "f1", "(int)").sampling(10).build();
    LogProbe probe2 = createProbeBuilder(PROBE_ID1, CLASS_NAME, "f2", "(int)").sampling(10).build();
    Configuration config =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(probe1, probe2)
            .add(new LogProbe.Sampling(1))
            .build();
    TestSnapshotListener listener = installProbes(config);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.onClass(testClass).call("main", "").get();
      assertEquals(48, result);
    }
    assertTrue(listener.snapshots.size() < 20, "actual snapshots: " + listener.snapshots.size());
  }

  @Test
  public void simpleConditionTest() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.and(
                            // this is always true
                            DSL.and(
                                // this reference is resolved directly from the snapshot
                                DSL.eq(DSL.ref("fld"), DSL.value(11)),
                                // this reference chain needs to use reflection
                                DSL.eq(
                                    DSL.getMember(
                                        DSL.getMember(
                                            DSL.getMember(DSL.ref("typed"), "fld"), "fld"),
                                        "msg"),
                                    DSL.value("hello"))),
                            DSL.and(
                                DSL.eq(DSL.ref("arg"), DSL.value("5")),
                                DSL.ge(DSL.ref(ValueReferences.DURATION_REF), DSL.value(0L))))),
                    "(fld == 11 && typed.fld.fld.msg == \"hello\") && (arg == '5' && @duration >= 0)"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.onClass(testClass).call("main", String.valueOf(i)).get();
      assertTrue((i == 2 && result == 2) || result == 3);
    }
    assertEquals(1, listener.snapshots.size());
    assertCaptureArgs(
        listener.snapshots.get(0).getCaptures().getReturn(), "arg", "java.lang.String", "5");
  }

  @Test
  public void lineProbeCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    LogProbe logProbe =
        createProbeBuilder(LINE_PROBE_ID1, CLASS_NAME, line)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.and(
                            // this is always true
                            DSL.and(
                                // this reference is resolved directly from the snapshot
                                DSL.eq(DSL.ref("fld"), DSL.value(11)),
                                // this reference chain needs to use reflection
                                DSL.eq(
                                    DSL.getMember(
                                        DSL.getMember(
                                            DSL.getMember(DSL.ref("typed"), "fld"), "fld"),
                                        "msg"),
                                    DSL.value("hello"))),
                            DSL.eq(DSL.ref("arg"), DSL.value("5")))),
                    "(fld == 11 && typed.fld.fld.msg == \"hello\") && arg == '5'"))
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.onClass(testClass).call("main", String.valueOf(i)).get();
      assertTrue((i == 2 && result == 2) || result == 3);
    }
    assertEquals(1, listener.snapshots.size());
    assertCaptureArgs(
        listener.snapshots.get(0).getCaptures().getLines().get(line),
        "arg",
        "java.lang.String",
        "5");
  }

  @Test
  public void staticFieldCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot19";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "process", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(DSL.eq(DSL.ref("strField"), DSL.value("foo"))), "strField == 'foo'"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "0").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, CapturedContext.CapturedValue> staticFields =
        snapshot.getCaptures().getReturn().getStaticFields();
    assertEquals(4, staticFields.size());
    assertEquals("foo", MoshiSnapshotTestHelper.getValue(staticFields.get("strField")));
    assertEquals("1001", MoshiSnapshotTestHelper.getValue(staticFields.get("intField")));
    assertEquals(
        String.valueOf(Math.PI), MoshiSnapshotTestHelper.getValue(staticFields.get("doubleField")));
    assertTrue(staticFields.containsKey("intArrayField"));
  }

  @Test
  public void simpleFalseConditionTest() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID2);
    LogProbe logProbe =
        createProbeBuilder(LINE_PROBE_ID2, CLASS_NAME, line)
            .when(
                new ProbeCondition(DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("5"))), "arg == '5'"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "0").get();
    assertEquals(3, result);
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void nullCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe logProbes =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.eq(
                            DSL.getMember(
                                DSL.getMember(DSL.getMember(DSL.ref("nullTyped"), "fld"), "fld"),
                                "msg"),
                            DSL.value("hello"))),
                    "nullTyped.fld.fld.msg == 'hello'"))
            .build();
    TestSnapshotListener listener = installProbes(logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    Snapshot snapshot = assertOneSnapshot(listener);
    List<EvaluationError> evaluationErrors = snapshot.getEvaluationErrors();
    assertEquals(1, evaluationErrors.size());
    assertEquals("nullTyped.fld.fld", evaluationErrors.get(0).getExpr());
    assertEquals("Cannot dereference field: fld", evaluationErrors.get(0).getMessage());
    assertEquals("Cannot dereference field: fld", snapshot.getMessage());
  }

  @Test
  public void shortCircuitingCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe logProbes =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.and(
                            DSL.isDefined(DSL.ref("@exception")),
                            DSL.contains(
                                DSL.getMember(DSL.ref("@exception"), "detailMessage"),
                                new StringValue("closed")))),
                    "isDefined(@exception) && contains(@exception.detailMessage, 'closed')"))
            .build();
    TestSnapshotListener listener = installProbes(logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    // no snapshot, no eval error, isDefined returns false and do not evaluate the second part
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void wellKnownClassesCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe logProbes =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.eq(
                            DSL.getMember(DSL.ref("maybeStr"), "value"), DSL.value("maybe foo"))),
                    "maybeStr.value == 'maybe foo'"))
            .build();
    TestSnapshotListener listener = installProbes(logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue maybeStrField =
        getFields(snapshot.getCaptures().getReturn().getArguments().get("this")).get("maybeStr");
    assertEquals(Optional.class.getTypeName(), maybeStrField.getDeclaredType());
    Map<String, CapturedContext.CapturedValue> maybeStrFields =
        (Map<String, CapturedContext.CapturedValue>) maybeStrField.getValue();
    CapturedContext.CapturedValue value = maybeStrFields.get("value");
    assertEquals("maybe foo", value.getValue());
  }

  @Test
  public void mergedProbesWithAllConditionsTrueTest() throws IOException, URISyntaxException {
    doMergedProbeConditions(
        new ProbeCondition(DSL.when(DSL.TRUE), "true"),
        new ProbeCondition(DSL.when(DSL.TRUE), "true"),
        2);
  }

  @Test
  public void mergedProbesWithAllConditionsFalseTest() throws IOException, URISyntaxException {
    doMergedProbeConditions(
        new ProbeCondition(DSL.when(DSL.FALSE), "false"),
        new ProbeCondition(DSL.when(DSL.FALSE), "false"),
        0);
  }

  @Test
  public void mergedProbesWithMainProbeConditionTest() throws IOException, URISyntaxException {
    doMergedProbeConditions(new ProbeCondition(DSL.when(DSL.TRUE), "true"), null, 2);
  }

  @Test
  public void mergedProbesWithAdditionalProbeConditionTest()
      throws IOException, URISyntaxException {
    doMergedProbeConditions(null, new ProbeCondition(DSL.when(DSL.TRUE), "true"), 2);
  }

  @Test
  public void mergedProbesWithMainProbeConditionFalseTest() throws IOException, URISyntaxException {
    doMergedProbeConditions(new ProbeCondition(DSL.when(DSL.FALSE), "false"), null, 1);
  }

  @Test
  public void mergedProbesWithAdditionalProbeConditionFalseTest()
      throws IOException, URISyntaxException {
    doMergedProbeConditions(null, new ProbeCondition(DSL.when(DSL.FALSE), "false"), 1);
  }

  private List<Snapshot> doMergedProbeConditions(
      ProbeCondition probeCondition1, ProbeCondition probeCondition2, int expectedSnapshots)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(probeCondition1)
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(probeCondition2)
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(expectedSnapshots, listener.snapshots.size());
    return listener.snapshots;
  }

  @Test
  public void mergedProbesConditionMainErrorAdditionalFalse()
      throws IOException, URISyntaxException {
    ProbeCondition condition1 =
        new ProbeCondition(
            DSL.when(
                DSL.eq(
                    DSL.getMember(
                        DSL.getMember(DSL.getMember(DSL.ref("nullTyped"), "fld"), "fld"), "msg"),
                    DSL.value("hello"))),
            "nullTyped.fld.fld.msg == 'hello'");
    ProbeCondition condition2 = new ProbeCondition(DSL.when(DSL.FALSE), "false");
    List<Snapshot> snapshots = doMergedProbeConditions(condition1, condition2, 1);
    List<EvaluationError> evaluationErrors = snapshots.get(0).getEvaluationErrors();
    Assertions.assertEquals(1, evaluationErrors.size());
    Assertions.assertEquals("nullTyped.fld.fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals("Cannot dereference field: fld", evaluationErrors.get(0).getMessage());
  }

  @Test
  public void mergedProbesConditionMainErrorAdditionalTrue()
      throws IOException, URISyntaxException {
    ProbeCondition condition1 =
        new ProbeCondition(
            DSL.when(
                DSL.eq(
                    DSL.getMember(
                        DSL.getMember(DSL.getMember(DSL.ref("nullTyped"), "fld"), "fld"), "msg"),
                    DSL.value("hello"))),
            "nullTyped.fld.fld.msg == 'hello'");
    ProbeCondition condition2 = new ProbeCondition(DSL.when(DSL.TRUE), "true");
    List<Snapshot> snapshots = doMergedProbeConditions(condition1, condition2, 2);
    List<EvaluationError> evaluationErrors = snapshots.get(0).getEvaluationErrors();
    Assertions.assertEquals(1, evaluationErrors.size());
    Assertions.assertEquals("nullTyped.fld.fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals("Cannot dereference field: fld", evaluationErrors.get(0).getMessage());
    assertNull(snapshots.get(1).getEvaluationErrors());
  }

  @Test
  public void mergedProbesConditionMainFalseAdditionalError()
      throws IOException, URISyntaxException {
    ProbeCondition condition1 = new ProbeCondition(DSL.when(DSL.FALSE), "false");
    ProbeCondition condition2 =
        new ProbeCondition(
            DSL.when(
                DSL.eq(
                    DSL.getMember(
                        DSL.getMember(DSL.getMember(DSL.ref("nullTyped"), "fld"), "fld"), "msg"),
                    DSL.value("hello"))),
            "nullTyped.fld.fld.msg == 'hello'");
    List<Snapshot> snapshots = doMergedProbeConditions(condition1, condition2, 1);
    List<EvaluationError> evaluationErrors = snapshots.get(0).getEvaluationErrors();
    Assertions.assertEquals(1, evaluationErrors.size());
    Assertions.assertEquals("nullTyped.fld.fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals("Cannot dereference field: fld", evaluationErrors.get(0).getMessage());
  }

  @Test
  public void mergedProbesConditionMainTrueAdditionalError()
      throws IOException, URISyntaxException {
    ProbeCondition condition1 = new ProbeCondition(DSL.when(DSL.TRUE), "true");
    ProbeCondition condition2 =
        new ProbeCondition(
            DSL.when(
                DSL.eq(
                    DSL.getMember(
                        DSL.getMember(DSL.getMember(DSL.ref("nullTyped"), "fld"), "fld"), "msg"),
                    DSL.value("hello"))),
            "nullTyped.fld.fld.msg == 'hello'");
    List<Snapshot> snapshots = doMergedProbeConditions(condition1, condition2, 2);
    assertNull(snapshots.get(0).getEvaluationErrors());
    List<EvaluationError> evaluationErrors = snapshots.get(1).getEvaluationErrors();
    Assertions.assertEquals(1, evaluationErrors.size());
    Assertions.assertEquals("nullTyped.fld.fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals("Cannot dereference field: fld", evaluationErrors.get(0).getMessage());
  }

  @Test
  public void mergedProbesConditionMixedLocation() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(new ProbeCondition(DSL.when(DSL.TRUE), "true"))
            .evaluateAt(MethodLocation.DEFAULT)
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(DSL.ge(DSL.ref("@duration"), DSL.value(0))), "@duration >= 0"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(2, listener.snapshots.size());
    assertNull(listener.snapshots.get(0).getEvaluationErrors());
    assertNull(listener.snapshots.get(1).getEvaluationErrors());
  }

  @Test
  public void mergedProbesDifferentSignature() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe probe1 = createMethodProbeAtExit(PROBE_ID1, CLASS_NAME, "doit", null);
    LogProbe probe2 =
        createMethodProbeAtExit(PROBE_ID2, CLASS_NAME, "doit", "int (java.lang.String)");
    LogProbe probe3 = createMethodProbeAtExit(PROBE_ID3, CLASS_NAME, "doit", "(String)");
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(3, listener.snapshots.size());
    assertNull(listener.snapshots.get(0).getEvaluationErrors());
    assertNull(listener.snapshots.get(1).getEvaluationErrors());
    assertNull(listener.snapshots.get(2).getEvaluationErrors());
  }

  @Test
  public void mergedProbesWithCaptureExpressionsMixed() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "doit", null)
            .evaluateAt(MethodLocation.EXIT)
            .captureSnapshot(false)
            .captureExpressions(
                Arrays.asList(
                    new LogProbe.CaptureExpression(
                        "typed_fld_fld_msg",
                        new ValueScript(
                            DSL.getMember(
                                DSL.getMember(DSL.getMember(DSL.ref("typed"), "fld"), "fld"),
                                "msg"),
                            "typed.fld.fld.msg"),
                        null),
                    new LogProbe.CaptureExpression(
                        "nullTyped_fld",
                        new ValueScript(
                            DSL.getMember(DSL.ref("nullTyped"), "fld"), "nullTyped.fld"),
                        null)))
            .build();
    LogProbe probe2 = createMethodProbeAtExit(PROBE_ID2, CLASS_NAME, "doit", null);
    TestSnapshotListener listener = installProbes(probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    // snapshot with Capture Expressions
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(2, snapshot0.getCaptures().getReturn().getCaptureExpressions().size());
    assertCaptureExpressions(
        snapshot0.getCaptures().getReturn(),
        "typed_fld_fld_msg",
        String.class.getTypeName(),
        "hello");
    assertCaptureExpressions(
        snapshot0.getCaptures().getReturn(), "nullTyped_fld", Object.class.getTypeName(), null);
    assertNull(snapshot0.getCaptures().getReturn().getArguments());
    assertNull(snapshot0.getCaptures().getReturn().getLocals());
    // Snapshot without Capture Expressions
    Snapshot snapshot1 = snapshots.get(1);
    assertNull(snapshot1.getCaptures().getReturn().getCaptureExpressions());
    assertCaptureArgs(snapshot1.getCaptures().getReturn(), "arg", String.class.getTypeName(), "1");
    assertCaptureLocals(
        snapshot1.getCaptures().getReturn(), "var1", Integer.TYPE.getTypeName(), "3");
    assertCaptureLocals(
        snapshot1.getCaptures().getReturn(), "@return", Integer.TYPE.getTypeName(), "3");
  }

  @Test
  public void mergedProbesWithDifferentCaptureExpressions() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "doit", null)
            .evaluateAt(MethodLocation.EXIT)
            .captureSnapshot(false)
            .captureExpressions(
                Arrays.asList(
                    new LogProbe.CaptureExpression(
                        "typed_fld_fld_msg",
                        new ValueScript(
                            DSL.getMember(
                                DSL.getMember(DSL.getMember(DSL.ref("typed"), "fld"), "fld"),
                                "msg"),
                            "typed.fld.fld.msg"),
                        null),
                    new LogProbe.CaptureExpression(
                        "nullTyped_fld",
                        new ValueScript(
                            DSL.getMember(DSL.ref("nullTyped"), "fld"), "nullTyped.fld"),
                        null)))
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, "doit", null)
            .evaluateAt(MethodLocation.EXIT)
            .captureSnapshot(false)
            .captureExpressions(
                Arrays.asList(
                    new LogProbe.CaptureExpression(
                        "var1", new ValueScript(DSL.ref("var1"), "var1"), null),
                    new LogProbe.CaptureExpression(
                        "this_fld",
                        new ValueScript(DSL.getMember(DSL.ref("this"), "fld"), "this.fld"),
                        null)))
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    // Snapshot 0
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(2, snapshot0.getCaptures().getReturn().getCaptureExpressions().size());
    assertCaptureExpressions(
        snapshot0.getCaptures().getReturn(),
        "typed_fld_fld_msg",
        String.class.getTypeName(),
        "hello");
    assertCaptureExpressions(
        snapshot0.getCaptures().getReturn(), "nullTyped_fld", Object.class.getTypeName(), null);
    assertNull(snapshot0.getCaptures().getReturn().getArguments());
    assertNull(snapshot0.getCaptures().getReturn().getLocals());
    // Snapshot 1
    Snapshot snapshot1 = snapshots.get(1);
    assertCaptureExpressions(
        snapshot1.getCaptures().getReturn(), "var1", Integer.class.getTypeName(), "3");
    assertCaptureExpressions(
        snapshot1.getCaptures().getReturn(), "this_fld", Integer.class.getTypeName(), "11");
    assertNull(snapshot1.getCaptures().getReturn().getArguments());
    assertNull(snapshot1.getCaptures().getReturn().getLocals());
  }

  @Test
  public void fields() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "f", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "f").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureFieldCount(snapshot.getCaptures().getEntry(), 5);
    assertCaptureFields(snapshot.getCaptures().getEntry(), "intValue", "int", "24");
    assertCaptureFields(snapshot.getCaptures().getEntry(), "doubleValue", "double", "3.14");
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "strValue", "java.lang.String", "foobar");
    assertCaptureFields(
        snapshot.getCaptures().getEntry(),
        "strList",
        "java.util.ArrayList",
        Arrays.asList("foo", "bar"));
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "strMap", "java.util.HashMap", Collections.emptyMap());
    assertCaptureFieldCount(snapshot.getCaptures().getReturn(), 5);
    assertCaptureFields(snapshot.getCaptures().getReturn(), "intValue", "int", "48");
    assertCaptureFields(snapshot.getCaptures().getReturn(), "doubleValue", "double", "3.14");
    assertCaptureFields(snapshot.getCaptures().getReturn(), "strValue", "java.lang.String", "done");
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "strList", "java.util.ArrayList", "[foo, bar, done]");
    Map<Object, Object> expectedMap = new HashMap<>();
    expectedMap.put("foo", "bar");
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "strMap", "java.util.HashMap", expectedMap);
  }

  @Test
  public void inheritedFields() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    final String INHERITED_CLASS_NAME = CLASS_NAME + "$Inherited";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, INHERITED_CLASS_NAME, "f", "()")
            .when(
                new ProbeCondition(
                    DSL.when(DSL.eq(DSL.ref("intValue"), DSL.value(24))), "intValue == 24"))
            .evaluateAt(MethodLocation.ENTRY)
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "inherited").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    // Only Declared fields in the current class are captured, not inherited fields
    assertCaptureFieldCount(snapshot.getCaptures().getEntry(), 5);
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "strValue", "java.lang.String", "foobar");
    assertCaptureFields(snapshot.getCaptures().getEntry(), "intValue", "int", "24");
    assertCaptureFieldCount(snapshot.getCaptures().getReturn(), 5);
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "strValue", "java.lang.String", "barfoo");
    assertCaptureFields(snapshot.getCaptures().getEntry(), "intValue", "int", "24");
  }

  @Test
  public void staticFields() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot15";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    long result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(4_000_000_001L, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, CapturedContext.CapturedValue> staticFields =
        snapshot.getCaptures().getEntry().getStaticFields();
    assertEquals(1, staticFields.size());
    CapturedContext.CapturedValue globalArray = staticFields.get("globalArray");
    assertNotNull(globalArray);
    assertEquals("long[]", globalArray.getType());
  }

  @Test
  public void staticInheritedFields() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot19";
    final String INHERITED_CLASS_NAME = CLASS_NAME + "$Inherited";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, INHERITED_CLASS_NAME, "f", "()")
            .when(
                new ProbeCondition(
                    DSL.when(DSL.eq(DSL.ref("intValue"), DSL.value(48))), "intValue == 48"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "inherited").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, CapturedContext.CapturedValue> staticFields =
        snapshot.getCaptures().getReturn().getStaticFields();
    // inherited static fields are not collected
    assertEquals(2, staticFields.size());
    assertEquals("barfoo", MoshiSnapshotTestHelper.getValue(staticFields.get("strValue")));
  }

  @Test
  public void staticLambda() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot07";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID1, CLASS_NAME, line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "static", "email@address").get();
    assertEquals(8, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    CapturedContext context = snapshot.getCaptures().getLines().get(line);
    assertNotNull(context);
    assertCaptureLocals(context, "idx", "int", "5");
  }

  @Test
  public void capturingLambda() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot07";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID2);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID2, CLASS_NAME, line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "capturing", "email@address").get();
    assertEquals(8, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID2, listener);
    CapturedContext context = snapshot.getCaptures().getLines().get(line);
    assertNotNull(context);
    assertCaptureLocals(context, "idx", "int", "5");
    assertCaptureFields(context, "strValue", "java.lang.String", "email@address");
  }

  @Test
  public void multiLambdas() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot07";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID3);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID3, CLASS_NAME, line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "multi", "FOO1,FOO2,FOO3").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID3, listener);
    CapturedContext context = snapshot.getCaptures().getLines().get(line);
    assertNotNull(context);
    assertCaptureArgs(context, "arg", String.class.getTypeName(), "FOO1,FOO2,FOO3");
  }

  @Test
  public void tracerInstrumentedClass() throws Exception {
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    final String CLASS_NAME = "com.datadog.debugger.jaxrs.MyResource";
    TestSnapshotListener listener = installMethodProbe(CLASS_NAME, "createResource", null);
    // load a class file that was previously instrumented by the DD tracer as JAX-RS resource
    Class<?> testClass =
        loadClass(CLASS_NAME, getClass().getResource("/MyResource.class").getFile());
    Object result =
        Reflect.onClass(testClass)
            .create()
            .call("createResource", (Object) null, (Object) null, 1)
            .get();
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, CapturedContext.CapturedValue> arguments =
        snapshot.getCaptures().getEntry().getArguments();
    // it's important there is no null key in this map, as Jackson is not happy about it
    // it's means here that argument names are not resolved correctly
    Assertions.assertFalse(arguments.containsKey(null));
    assertEquals(4, arguments.size());
    assertTrue(arguments.containsKey("this"));
    assertTrue(arguments.containsKey("apiKey"));
    assertTrue(arguments.containsKey("uriInfo"));
    assertTrue(arguments.containsKey("value"));
  }

  @Test
  public void noCodeMethods() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot09";
    LogProbe nativeMethodProbe = createMethodProbe(PROBE_ID1, CLASS_NAME, "nativeMethod", "()");
    LogProbe abstractMethodProbe = createMethodProbe(PROBE_ID2, CLASS_NAME, "abstractMethod", "()");
    TestSnapshotListener listener = installProbes(nativeMethodProbe, abstractMethodProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(1, result);
    ArgumentCaptor<ProbeId> probeIdCaptor = ArgumentCaptor.forClass(ProbeId.class);
    ArgumentCaptor<String> strCaptor = ArgumentCaptor.forClass(String.class);
    verify(probeStatusSink, times(2)).addError(probeIdCaptor.capture(), strCaptor.capture());
    assertEquals(PROBE_ID1.getId(), probeIdCaptor.getAllValues().get(0).getId());
    assertEquals("Cannot instrument an abstract or native method", strCaptor.getAllValues().get(0));
    assertEquals(PROBE_ID2.getId(), probeIdCaptor.getAllValues().get(1).getId());
    assertEquals("Cannot instrument an abstract or native method", strCaptor.getAllValues().get(1));
  }

  @Test
  public void duplicateClassDefinition() throws Exception {
    // this test reproduces a very specific case where we get:
    // java.lang.LinkageError: loader utils.MemClassLoader @1f7ef9ea
    // attempted duplicate class definition for com.datadog.debugger.CapturedSnapshot12.
    // (com.datadog.debugger.CapturedSnapshot12 is in unnamed module of loader utils.MemClassLoader
    // @1f7ef9ea, parent loader 'app')
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot12";
    LogProbe abstractMethodProbe = createMethodProbe(PROBE_ID, CLASS_NAME, "<init>", null);
    TestSnapshotListener listener = installProbes(abstractMethodProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    assertNotNull(testClass);
  }

  @Test
  public void overloadedMethods() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot16";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "overload", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(63, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 1, PROBE_ID);
    assertCaptureReturnValue(snapshots.get(0).getCaptures().getReturn(), "int", "42");
    assertEquals(1, snapshots.get(0).getCaptures().getEntry().getArguments().size());
  }

  @Test
  public void noDebugInfoEmptyMethod() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot03";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "empty", null));
    Map<String, byte[]> classFileBuffers = compile(CLASS_NAME, SourceCompiler.DebugInfo.NONE, "8");
    Class<?> testClass = loadClass(CLASS_NAME, classFileBuffers);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(48, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void instrumentTheWorld() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot01";
    Map<String, byte[]> classFileBuffers = compile(CLASS_NAME);
    TestSnapshotListener listener =
        setupInstrumentTheWorldTransformer(
            null, getClass().getResource("/include-files/singleClass.txt").getPath());
    Class<?> testClass;
    try {
      testClass = loadClass(CLASS_NAME, classFileBuffers);
    } finally {
      instr.removeTransformer(currentTransformer);
    }
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    assertEquals(1, listener.snapshots.size());
    ProbeImplementation probeImplementation = listener.snapshots.get(0).getProbe();
    assertFalse(((CapturedContextProbe) probeImplementation).isCaptureSnapshot());
    assertEquals("main", probeImplementation.getLocation().getMethod());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/exclude-files/singleClass.txt", "/exclude-files/startsWithClass.txt"})
  public void instrumentTheWorld_excludeClass(String excludeFileName) throws Exception {
    final String CLASS_NAME = "CapturedSnapshot01";
    Map<String, byte[]> classFileBuffers = compile(CLASS_NAME);
    URL resource = getClass().getResource(excludeFileName);
    TestSnapshotListener listener =
        setupInstrumentTheWorldTransformer(
            resource.getPath(), getClass().getResource("/include-files/singleClass.txt").getPath());
    Class<?> testClass;
    try {
      testClass = loadClass(CLASS_NAME, classFileBuffers);
    } finally {
      instr.removeTransformer(currentTransformer);
    }
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void objectDynamicType() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot17";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "processWithArg", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(50, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureArgs(snapshot.getCaptures().getEntry(), "obj", "java.lang.Integer", "42");
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "objField", "java.lang.String", "foobar");
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "result", "java.lang.Integer", "50");
    assertCaptureReturnValue(snapshot.getCaptures().getReturn(), "java.lang.Integer", "50");
  }

  @Test
  public void exceptionAsLocalVariable() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot18";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener =
        installProbes(createLineProbe(LINE_PROBE_ID1, CLASS_NAME, line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    Map<String, String> expectedFields = new HashMap<>();
    expectedFields.put("detailMessage", "For input string: \"a\"");
    assertCaptureLocals(
        snapshot.getCaptures().getLines().get(line),
        "ex",
        "java.lang.NumberFormatException",
        expectedFields);
  }

  @Test
  public void evaluateAtEntry() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe logProbes =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "int (java.lang.String)")
            .when(
                new ProbeCondition(DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("1"))), "arg == '1'"))
            .evaluateAt(MethodLocation.ENTRY)
            .build();
    TestSnapshotListener listener = installProbes(logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void evaluateAtExit() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe logProbes =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(DSL.eq(DSL.ref("@return"), DSL.value(3))), "@return == 3"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void evaluateAtExitFalse() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe logProbes =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(DSL.eq(DSL.ref("@return"), DSL.value(0))), "@return == 0"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, listener.snapshots.size());
    assertTrue(listener.skipped);
    assertEquals(DebuggerContext.SkipCause.CONDITION, listener.cause);
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void uncaughtExceptionConditionLocalVar() throws IOException, URISyntaxException {
    if (Config.get().getDynamicInstrumentationLocalVarHoistingLevel() < 2) {
      // this test requires local var hoisting level 2 (aggressive)
      // because we need to hoist long local variables (2 slots)
      return;
    }
    final String CLASS_NAME = "CapturedSnapshot05";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "(String)")
            .when(
                new ProbeCondition(DSL.when(DSL.ge(DSL.ref("after"), DSL.value(0))), "after >= 0"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.onClass(testClass).call("main", "triggerUncaughtException").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureThrowable(
        snapshot.getCaptures().getReturn(),
        "CapturedSnapshot05$CustomException",
        "oops",
        "CapturedSnapshot05.triggerUncaughtException",
        8);
    assertNull(snapshot.getEvaluationErrors());
    // after is 0 because the exception is thrown before the assignment and local var initialized
    // at the beginning of the method by instrumentation
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "after", "long", "0");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void uncaughtExceptionCaptureLocalVars() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe = createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "uncaughtException", null);
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.onClass(testClass).call("main", "uncaughtException").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureThrowable(
        snapshot.getCaptures().getReturn(),
        "java.lang.RuntimeException",
        "oops",
        "com.datadog.debugger.CapturedSnapshot31.uncaughtException",
        51);
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void methodProbeLocalVarsLocalScopes() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe = createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "localScopes", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "localScopes").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "@return", "int", "42");
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "localVar", "int", "42");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void methodProbeLocalVarsDeepScopes() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe = createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "deepScopes", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "deepScopes").get();
    assertEquals(4, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    // localVarL4 can not be captured/hoisted because same name, same slot
    //    LocalVariableTable:
    //    Start  Length  Slot  Name   Signature
    //       59       3     6 localVarL4   I
    //       65       3     6 localVarL4   I
    //       71       3     6 localVarL4   I
    //       29      45     5 localVarL3   I
    //       20      54     4 localVarL2   I
    //       13      64     3 localVarL1   I
    //        0      79     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
    //        0      79     1   arg   Ljava/lang/String;
    //        2      77     2 localVarL0   I
    // localVarL4 cannot be hoisted in safe mode because of the same name and slot
    assertEquals(5, snapshot.getCaptures().getReturn().getLocals().size());
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "localVarL0", "int", "0");
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "localVarL1", "int", "1");
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "localVarL2", "int", "2");
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "localVarL3", "int", "3");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void methodProbeExceptionLocalVars() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe = createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "caughtException", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "illegalState").get();
    assertEquals(0, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    Map<String, String> expectedFields = new HashMap<>();
    expectedFields.put("detailMessage", "state");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(),
        "ex",
        IllegalStateException.class.getTypeName(),
        expectedFields);
    listener.snapshots.clear();
    result = Reflect.onClass(testClass).call("main", "illegalArgument").get();
    assertEquals(0, result);
    snapshot = assertOneSnapshot(listener);
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    expectedFields = new HashMap<>();
    expectedFields.put("detailMessage", "argument");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(),
        "ex",
        IllegalArgumentException.class.getTypeName(),
        expectedFields);
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void overlappingLocalVarSlot() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe = createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "overlappingSlots", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "overlappingSlots").get();
    assertEquals(5, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    // i local var cannot be hoisted because of overlapping slots and name
    assertFalse(snapshot.getCaptures().getReturn().getLocals().containsKey("i"));
    assertTrue(snapshot.getCaptures().getReturn().getLocals().containsKey("subStr"));
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void duplicateLocalDifferentScope() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe =
        createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "duplicateLocalDifferentScope", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "duplicateLocalDifferentScope").get();
    assertEquals(28, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(1, snapshot.getCaptures().getReturn().getLocals().size());
    // ch cannot be hoisted in safe mode, because it has the duplicate name with different slot
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "@return", Integer.TYPE.getTypeName(), "28");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void mixingIntAndLongWhenHoisting() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe = createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "mixingIntAndLong", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "mixingIntAndLong").get();
    assertEquals(1626, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    // no hoisting in safe mode: long local variable and 2-slots type (forbidden slots)
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "l", Long.TYPE.getTypeName(), "1626");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "@return", Integer.TYPE.getTypeName(), "1626");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void mixingIntAndCharWhenHoisting() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe = createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "mixingIntAndChar", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "mixingIntAndChar").get();
    assertEquals(-327, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "i", Integer.TYPE.getTypeName(), "-327");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "@return", Integer.TYPE.getTypeName(), "-327");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void mixingIntAndRefTypeWhenHoisting() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe =
        createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "mixingIntAndRefType", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "mixingIntAndRefType").get();
    assertEquals(19, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    assertCaptureLocals(snapshot.getCaptures().getReturn(), "i", Integer.TYPE.getTypeName(), "19");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "@return", Integer.TYPE.getTypeName(), "19");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void sameSlotAndTypeDifferentName() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe =
        createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "sameSlotAndTypeDifferentName", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "sameSlotAndTypeDifferentName").get();
    assertEquals(28, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    // r and o cannot be hoisted in safe mode, because they have the same slot
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(),
        "p",
        String.class.getTypeName(),
        "sameSlotAndTypeDifferentName");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "@return", Integer.TYPE.getTypeName(), "28");
  }

  @Test
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "we cannot get local variable debug info")
  public void sameSlotAndNameOneReturn() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot31";
    LogProbe probe =
        createMethodProbeAtExit(PROBE_ID, CLASS_NAME, "sameSlotAndNameOneReturn", "(String)");
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "sameSlotAndNameOneReturn").get();
    assertEquals(24, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    // o is not hoisted in safe mode, because it has the same slot and name on different range
    assertEquals(2, snapshot.getCaptures().getReturn().getLocals().size());
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "result", Integer.TYPE.getTypeName(), "24");
    assertCaptureLocals(
        snapshot.getCaptures().getReturn(), "@return", Integer.TYPE.getTypeName(), "24");
  }

  @Test
  public void enumConstructorArgs() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot23";
    final String ENUM_CLASS = CLASS_NAME + "$MyEnum";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, ENUM_CLASS, "<init>", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(2, result);
    assertSnapshots(listener, 3, PROBE_ID);
    Map<String, CapturedContext.CapturedValue> arguments =
        listener.snapshots.get(0).getCaptures().getEntry().getArguments();
    assertEquals(3, arguments.size());
    assertTrue(arguments.containsKey("this"));
    assertTrue(arguments.containsKey("p1")); // this the hidden ordinal arg of an enum
    assertTrue(arguments.containsKey("strValue"));
  }

  @Test
  public void enumValues() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot23";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, CLASS_NAME, "convert", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureReturnValue(
        snapshot.getCaptures().getReturn(),
        "com.datadog.debugger.CapturedSnapshot23$MyEnum",
        "TWO");
  }

  @Test
  public void enumCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot23";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "convert", null)
            .evaluateAt(MethodLocation.EXIT)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.and(
                            DSL.eq(DSL.ref("@return"), DSL.value("TWO")),
                            DSL.eq(DSL.ref("@return"), DSL.value("MyEnum.TWO")),
                            DSL.eq(
                                DSL.ref("@return"),
                                DSL.value("com.datadog.debugger.CapturedSnapshot23$MyEnum.TWO")))),
                    "@return == 'TWO' && @return == 'MyEnum.TWO' && @return == 'com.datadog.debugger.CapturedSnapshot23$MyEnum.TWO'"))
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "2").get();
    assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureReturnValue(
        snapshot.getCaptures().getReturn(),
        "com.datadog.debugger.CapturedSnapshot23$MyEnum",
        "TWO");
  }

  @Test
  public void recursiveCapture() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot24";
    final String INNER_CLASS = CLASS_NAME + "$Holder";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, INNER_CLASS, "size", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    assertEquals(1, result);
  }

  @Test
  public void recursiveCaptureException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot24";
    final String INNER_CLASS = CLASS_NAME + "$HolderWithException";
    TestSnapshotListener listener =
        installProbes(createMethodProbe(PROBE_ID, INNER_CLASS, "size", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.onClass(testClass).call("main", "exception").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      assertEquals("not supported", ex.getCause().getCause().getMessage());
    }
  }

  @Test
  public void unknownCollectionCount() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot24";
    Snapshot snapshot = doUnknownCount(CLASS_NAME);
    assertEquals(
        "Unsupported Collection class: com.datadog.debugger.CapturedSnapshot24$Holder",
        snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void unknownMapCount() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot26";
    Snapshot snapshot = doUnknownCount(CLASS_NAME);
    assertEquals(
        "Unsupported Map class: com.datadog.debugger.CapturedSnapshot26$Holder",
        snapshot.getEvaluationErrors().get(0).getMessage());
  }

  private Snapshot doUnknownCount(String CLASS_NAME) throws IOException, URISyntaxException {
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", null)
            .when(
                new ProbeCondition(
                    DSL.when(DSL.ge(DSL.len(DSL.ref("holder")), DSL.value(0))), "len(holder) >= 0"))
            .build();
    TestSnapshotListener listener = installProbes(logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "").get();
    Assertions.assertEquals(1, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals("len(holder)", snapshot.getEvaluationErrors().get(0).getExpr());
    return snapshot;
  }

  @Test
  public void beforeForLoopLineProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    TestSnapshotListener listener = installLineProbe(LINE_PROBE_ID1, CLASS_NAME, line);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "synchronizedBlock").get();
    assertEquals(76, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    assertCaptureLocals(snapshot.getCaptures().getLines().get(line), "count", "int", "31");
  }

  @Test
  public void dupLineProbeSameTemplate() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    final String LOG_TEMPLATE = "msg1={typed.fld.fld.msg}";
    int line1 = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID3);
    int line2 = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID4);
    assertTrue(line1 == line2);
    LogProbe probe1 =
        createProbeBuilder(LINE_PROBE_ID3, CLASS_NAME, line1)
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    LogProbe probe2 =
        createProbeBuilder(LINE_PROBE_ID4, CLASS_NAME, line2)
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, LINE_PROBE_ID3, LINE_PROBE_ID4);
    for (Snapshot snapshot : snapshots) {
      assertEquals("msg1=hello", snapshot.getMessage());
    }
  }

  @Test
  public void keywordRedaction() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot27";
    final String LOG_TEMPLATE =
        "arg={arg} secret={secret} password={this.password} fromMap={strMap['password']}";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", null)
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "secret123").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(
        "arg=secret123 secret={"
            + REDACTED_VALUE
            + "} password={"
            + REDACTED_VALUE
            + "} fromMap={"
            + REDACTED_VALUE
            + "}",
        snapshot.getMessage());
    CapturedContext.CapturedValue secretLocalVar =
        snapshot.getCaptures().getReturn().getLocals().get("secret");
    CapturedContext.CapturedValue secretValued =
        VALUE_ADAPTER.fromJson(secretLocalVar.getStrValue());
    assertEquals(REDACTED_IDENT_REASON, secretValued.getNotCapturedReason());
    Map<String, CapturedContext.CapturedValue> thisFields =
        getFields(snapshot.getCaptures().getReturn().getArguments().get("this"));
    CapturedContext.CapturedValue passwordField = thisFields.get("password");
    assertEquals(REDACTED_IDENT_REASON, passwordField.getNotCapturedReason());
    Map<String, String> strMap = (Map<String, String>) thisFields.get("strMap").getValue();
    assertNull(strMap.get("password"));
  }

  @Test
  public void keywordRedactionConditions() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot27";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "doit", null)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.contains(
                            DSL.getMember(DSL.ref("this"), "password"), new StringValue("123"))),
                    "contains(this.password, '123')"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, "doit", null)
            .when(
                new ProbeCondition(
                    DSL.when(DSL.eq(DSL.ref("password"), DSL.value("123"))), "password == '123'"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    LogProbe probe3 =
        createProbeBuilder(PROBE_ID3, CLASS_NAME, "doit", null)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.eq(
                            DSL.index(DSL.ref("strMap"), DSL.value("password")), DSL.value("123"))),
                    "strMap['password'] == '123'"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "secret123").get();
    Assertions.assertEquals(42, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 3, PROBE_ID1, PROBE_ID2, PROBE_ID3);
    assertEquals(1, snapshots.get(0).getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'this.password' was redacted",
        snapshots.get(0).getEvaluationErrors().get(0).getMessage());
    assertEquals(1, snapshots.get(1).getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'password' was redacted",
        snapshots.get(1).getEvaluationErrors().get(0).getMessage());
    assertEquals(1, snapshots.get(2).getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'strMap[\"password\"]' was redacted",
        snapshots.get(2).getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void typeRedactionBlockedProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot27";
    Config config = mock(Config.class);
    when(config.getDynamicInstrumentationRedactedTypes())
        .thenReturn("com.datadog.debugger.CapturedSnapshot27");
    Redaction.addUserDefinedTypes(config);
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", null)
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "secret123").get();
    Assertions.assertEquals(42, result);
    assertEquals(0, listener.snapshots.size());
    InstrumentationResult instrumentationResult =
        instrumentationListener.results.get(PROBE_ID.getId());
    assertTrue(instrumentationResult.isBlocked());
  }

  @Test
  public void typeRedactionSnapshot() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot27";
    final String LOG_TEMPLATE =
        "arg={arg} credentials={creds} user={this.creds.user} code={creds.secretCode} dave={credMap['dave'].user}";
    Config config = mock(Config.class);
    when(config.getDynamicInstrumentationRedactedTypes())
        .thenReturn("com.datadog.debugger.CapturedSnapshot27$Creds");
    Redaction.addUserDefinedTypes(config);
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", null)
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "secret123").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(
        "arg=secret123 credentials={"
            + REDACTED_VALUE
            + "} user={"
            + REDACTED_VALUE
            + "} code={"
            + REDACTED_VALUE
            + "} dave={"
            + REDACTED_VALUE
            + "}",
        snapshot.getMessage());
    Map<String, CapturedContext.CapturedValue> thisFields =
        getFields(snapshot.getCaptures().getReturn().getArguments().get("this"));
    CapturedContext.CapturedValue credsField = thisFields.get("creds");
    assertEquals(REDACTED_TYPE_REASON, credsField.getNotCapturedReason());
    Map<String, String> credMap = (Map<String, String>) thisFields.get("credMap").getValue();
    assertNull(credMap.get("dave"));
  }

  @Test
  public void typeRedactionCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot27";
    Config config = mock(Config.class);
    when(config.getDynamicInstrumentationRedactedTypes())
        .thenReturn("com.datadog.debugger.CapturedSnapshot27$Creds");
    Redaction.addUserDefinedTypes(config);
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "doit", null)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.contains(
                            DSL.getMember(DSL.getMember(DSL.ref("this"), "creds"), "secretCode"),
                            new StringValue("123"))),
                    "contains(this.creds.secretCode, '123')"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, "doit", null)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.eq(DSL.getMember(DSL.ref("creds"), "secretCode"), DSL.value("123"))),
                    "creds.secretCode == '123'"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    LogProbe probe3 =
        createProbeBuilder(PROBE_ID3, CLASS_NAME, "doit", null)
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.eq(DSL.index(DSL.ref("credMap"), DSL.value("dave")), DSL.value("123"))),
                    "credMap['dave'] == '123'"))
            .captureSnapshot(true)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    TestSnapshotListener listener = installProbes(probe1, probe2, probe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "secret123").get();
    Assertions.assertEquals(42, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 3, PROBE_ID1, PROBE_ID2, PROBE_ID3);
    assertEquals(1, snapshots.get(0).getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'this.creds' was redacted",
        snapshots.get(0).getEvaluationErrors().get(0).getMessage());
    assertEquals(1, snapshots.get(1).getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'creds' was redacted",
        snapshots.get(1).getEvaluationErrors().get(0).getMessage());
    assertEquals(1, snapshots.get(2).getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'credMap[\"dave\"]' was redacted",
        snapshots.get(2).getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void ensureCallingSamplingMethodProbe() throws IOException, URISyntaxException {
    doSamplingTest(this::methodProbe, 1, 1);
  }

  @Test
  public void ensureCallingSamplingProbeCondition() throws IOException, URISyntaxException {
    doSamplingTest(this::simpleConditionTest, 1, 1);
  }

  @Test
  public void ensureCallingSamplingProbeConditionError() throws IOException, URISyntaxException {
    doSamplingTest(this::nullCondition, 1, 1);
  }

  @Test
  public void ensureCallingSamplingDupMethodProbeCondition()
      throws IOException, URISyntaxException {
    doSamplingTest(this::mergedProbesWithAdditionalProbeConditionTest, 2, 2);
  }

  @Test
  public void ensureCallingSamplingLineProbe() throws IOException, URISyntaxException {
    doSamplingTest(this::singleLineProbe, 1, 1);
  }

  @Test
  public void ensureCallingSamplingLineProbeCondition() throws IOException, URISyntaxException {
    doSamplingTest(this::lineProbeCondition, 1, 1);
  }

  private void doSamplingTest(TestMethod testRun, int expectedGlobalCount, int expectedProbeCount)
      throws IOException, URISyntaxException {
    MockSampler probeSampler = new MockSampler();
    MockSampler globalSampler = new MockSampler();
    ProbeRateLimiter.setSamplerSupplier(rate -> rate < 101 ? probeSampler : globalSampler);
    ProbeRateLimiter.setGlobalSnapshotRate(1000);
    try {
      testRun.run();
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
    assertEquals(expectedGlobalCount, globalSampler.getCallCount());
    assertEquals(expectedProbeCount, probeSampler.getCallCount());
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_17)
  public void record() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot29";
    final String RECORD_NAME = "com.datadog.debugger.MyRecord1";
    LogProbe probe1 = createMethodProbeAtExit(PROBE_ID, RECORD_NAME, "age", null);
    TestSnapshotListener listener = installProbes(probe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME, "17");
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureReturnValue(snapshot.getCaptures().getReturn(), "int", "42");
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "firstName", String.class.getTypeName(), "john");
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "lastName", String.class.getTypeName(), "doe");
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "age", Integer.TYPE.getTypeName(), "42");
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_17)
  public void lineRecord() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot29";
    final String RECORD_NAME = "com.datadog.debugger.MyRecord2";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    LogProbe probe1 = createLineProbe(LINE_PROBE_ID1, RECORD_NAME, line);
    TestSnapshotListener listener = installProbes(probe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME, "17");
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(LINE_PROBE_ID1, listener);
    CapturedContext capturedContext = snapshot.getCaptures().getLines().get(line);
    assertCaptureArgs(capturedContext, "firstName", String.class.getTypeName(), "john");
    assertCaptureArgs(capturedContext, "lastName", String.class.getTypeName(), "doe");
    assertCaptureArgs(capturedContext, "age", Integer.TYPE.getTypeName(), "42");
    assertCaptureFields(capturedContext, "firstName", String.class.getTypeName(), (String) null);
    assertCaptureFields(capturedContext, "lastName", String.class.getTypeName(), (String) null);
    assertCaptureFields(capturedContext, "age", Integer.TYPE.getTypeName(), "0");
  }

  @Test
  public void allProbesSameMethod() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    final String METRIC_NAME = "count";
    Where where = Where.of(CLASS_NAME, "main", null);
    Configuration configuration =
        Configuration.builder()
            .add(
                SpanDecorationProbe.builder()
                    .probeId(PROBE_ID)
                    .where(where)
                    .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
                    .decorate(
                        new SpanDecorationProbe.Decoration(
                            null,
                            Arrays.asList(
                                new SpanDecorationProbe.Tag(
                                    "tag1",
                                    new SpanDecorationProbe.TagValue(
                                        "value1", parseTemplate("value1"))))))
                    .build())
            .add(SpanProbe.builder().probeId(PROBE_ID1).where(where).build())
            .add(
                MetricProbe.builder()
                    .probeId(PROBE_ID2)
                    .metricName(METRIC_NAME)
                    .kind(MetricProbe.MetricKind.COUNT)
                    .where(where)
                    .build())
            .add(LogProbe.builder().probeId(PROBE_ID3).where(where).build())
            .add(new TriggerProbe(PROBE_ID4, where))
            .build();

    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    TestTraceInterceptor traceInterceptor = new TestTraceInterceptor();
    tracer.addTraceInterceptor(traceInterceptor);
    try {
      TestSnapshotListener snapshotListener = installProbes(configuration);
      DebuggerContext.initTracer(new DebuggerTracer(mock(ProbeStatusSink.class)));
      MetricProbesInstrumentationTest.MetricForwarderListener metricListener =
          new MetricProbesInstrumentationTest.MetricForwarderListener();
      DebuggerContext.initMetricForwarder(metricListener);
      Class<?> testClass = compileAndLoadClass(CLASS_NAME);
      int result = Reflect.onClass(testClass).call("main", "1").get();
      // log probe
      assertEquals(3, result);
      List<Snapshot> snapshots = snapshotListener.snapshots;
      assertEquals(
          1,
          snapshots.size(),
          "More than one probe emitted a snapshot: "
              + snapshots.stream()
                  .map(snapshot -> snapshot.getProbe().getId())
                  .collect(Collectors.toList()));
      Snapshot snapshot = snapshots.get(0);
      assertEquals(PROBE_ID3.getId(), snapshot.getProbe().getId());
      // span (deco) probe
      assertEquals(1, traceInterceptor.getTrace().size());
      MutableSpan span = traceInterceptor.getFirstSpan();
      assertEquals(CLASS_NAME + ".main", span.getResourceName());
      assertEquals(PROBE_ID1.getId(), span.getTag("debugger.probeid"));
      assertEquals("value1", span.getTag("tag1"));
      // correlation between log and created span
      assertEquals(snapshot.getSpanId(), String.valueOf(((DDSpan) span).getSpanId()));
      // metric probe
      assertTrue(metricListener.counters.containsKey(METRIC_NAME));
      assertEquals(1, metricListener.counters.get(METRIC_NAME).longValue());
      assertArrayEquals(
          new String[] {"debugger.probeid:" + PROBE_ID2.getId()}, metricListener.lastTags);
    } finally {
      TracerInstaller.forceInstallGlobalTracer(null);
    }
  }

  @Test
  public void captureExpressions() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", null)
            .evaluateAt(MethodLocation.EXIT)
            .captureSnapshot(false)
            .captureExpressions(
                Arrays.asList(
                    new LogProbe.CaptureExpression(
                        "typed_fld_fld_msg",
                        new ValueScript(
                            DSL.getMember(
                                DSL.getMember(DSL.getMember(DSL.ref("typed"), "fld"), "fld"),
                                "msg"),
                            "typed.fld.fld.msg"),
                        null),
                    new LogProbe.CaptureExpression(
                        "nullTyped_fld",
                        new ValueScript(
                            DSL.getMember(DSL.ref("nullTyped"), "fld"), "nullTyped.fld"),
                        null)))
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(2, snapshot.getCaptures().getReturn().getCaptureExpressions().size());
    assertCaptureExpressions(
        snapshot.getCaptures().getReturn(),
        "typed_fld_fld_msg",
        String.class.getTypeName(),
        "hello");
    assertCaptureExpressions(
        snapshot.getCaptures().getReturn(), "nullTyped_fld", Object.class.getTypeName(), null);
  }

  @Test
  public void captureExpressionsLineProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID3);
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, line)
            .captureSnapshot(false)
            .captureExpressions(
                Arrays.asList(
                    new LogProbe.CaptureExpression(
                        "typed_fld_fld_msg",
                        new ValueScript(
                            DSL.getMember(
                                DSL.getMember(DSL.getMember(DSL.ref("typed"), "fld"), "fld"),
                                "msg"),
                            "typed.fld.fld.msg"),
                        null),
                    new LogProbe.CaptureExpression(
                        "nullTyped_fld",
                        new ValueScript(
                            DSL.getMember(DSL.ref("nullTyped"), "fld"), "nullTyped.fld"),
                        null)))
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext capturedContext = snapshot.getCaptures().getLines().get(line);
    assertEquals(2, capturedContext.getCaptureExpressions().size());
    assertCaptureExpressions(
        capturedContext, "typed_fld_fld_msg", String.class.getTypeName(), "hello");
    assertCaptureExpressions(capturedContext, "nullTyped_fld", Object.class.getTypeName(), null);
  }

  @Test
  public void captureExpressionsWithCaptureLimits() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", null)
            .evaluateAt(MethodLocation.EXIT)
            .captureSnapshot(false)
            .captureExpressions(
                Arrays.asList(
                    new LogProbe.CaptureExpression(
                        "typed_fld_fld_msg",
                        new ValueScript(
                            DSL.getMember(
                                DSL.getMember(DSL.getMember(DSL.ref("typed"), "fld"), "fld"),
                                "msg"),
                            "typed.fld.fld.msg"),
                        new LogProbe.Capture(3, 100, 3, 20)),
                    new LogProbe.CaptureExpression(
                        "typed",
                        new ValueScript(DSL.ref("typed"), "typed"),
                        new LogProbe.Capture(1, 1, 3, 20))))
            .build();
    TestSnapshotListener listener = installProbes(probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.onClass(testClass).call("main", "1").get();
    assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue msgValue =
        deserializeCapturedValue(
            snapshot.getCaptures().getReturn().getCaptureExpressions().get("typed_fld_fld_msg"));
    assertEquals("hel", msgValue.getValue());
    assertEquals("truncated", msgValue.getNotCapturedReason());
    CapturedContext.CapturedValue typedValue =
        deserializeCapturedValue(
            snapshot.getCaptures().getReturn().getCaptureExpressions().get("typed"));
    Map<String, CapturedContext.CapturedValue> fields =
        (Map<String, CapturedContext.CapturedValue>) typedValue.getValue();
    CapturedContext.CapturedValue fldValue = fields.get("fld");
    assertEquals("depth", fldValue.getNotCapturedReason());
  }

  private TestSnapshotListener setupInstrumentTheWorldTransformer(
      String excludeFileName, String includeFileName) {
    Config config = mock(Config.class);
    when(config.isDynamicInstrumentationEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationClassFileDumpEnabled()).thenReturn(true);
    when(config.getDynamicInstrumentationInstrumentTheWorld()).thenReturn("method");
    when(config.getDynamicInstrumentationExcludeFiles()).thenReturn(excludeFileName);
    when(config.getDynamicInstrumentationIncludeFiles()).thenReturn(includeFileName);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    TestSnapshotListener listener = new TestSnapshotListener(config, mock(ProbeStatusSink.class));
    DebuggerAgentHelper.injectSink(listener);
    currentTransformer =
        DebuggerAgent.setupInstrumentTheWorldTransformer(
            config,
            instr,
            new DebuggerSink(
                config, new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false)));
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    return listener;
  }

  private Snapshot assertOneSnapshot(TestSnapshotListener listener) {
    return assertOneSnapshot(PROBE_ID, listener);
  }

  private Snapshot assertOneSnapshot(ProbeId probeId, TestSnapshotListener listener) {
    assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    assertEquals(probeId.getId(), snapshot.getProbe().getId());
    return snapshot;
  }
}
