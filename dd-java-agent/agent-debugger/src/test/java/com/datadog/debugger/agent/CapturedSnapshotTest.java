package com.datadog.debugger.agent;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static com.datadog.debugger.util.MoshiSnapshotHelper.DEPTH_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELD_COUNT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static com.datadog.debugger.util.TestHelper.setFieldInConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compile;
import static utils.InstrumentationTestHelper.compileAndLoadClass;
import static utils.InstrumentationTestHelper.loadClass;
import static utils.TestHelper.getFixtureContent;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.datadog.debugger.util.SerializerWithLimits;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.*;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import groovy.lang.GroovyClassLoader;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;
import org.joor.Reflect;
import org.joor.ReflectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import utils.SourceCompiler;

public class CapturedSnapshotTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);
  private static final String SERVICE_NAME = "service-name";
  private static final JsonAdapter<CapturedContext.CapturedValue> VALUE_ADAPTER =
      new MoshiSnapshotTestHelper.CapturedValueAdapter();
  private static final JsonAdapter<Map<String, Object>> GENERIC_ADAPTER =
      MoshiHelper.createGenericAdapter();

  private Instrumentation instr = ByteBuddyAgent.install();
  private ClassFileTransformer currentTransformer;
  private ProbeStatusSink probeStatusSink;

  @BeforeEach
  public void before() {
    setFieldInConfig(Config.get(), "debuggerCaptureTimeout", 200);
  }

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
    ProbeRateLimiter.resetAll();
    Assertions.assertFalse(DebuggerContext.isInProbe());
  }

  @Test
  public void methodNotFound() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "foobar", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(2, result);
    verify(probeStatusSink)
        .addError(eq(PROBE_ID), eq("Cannot find method CapturedSnapshot01::foobar"));
  }

  @Test
  public void methodProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNotNull(snapshot.getCaptures().getEntry());
    Assertions.assertNotNull(snapshot.getCaptures().getReturn());
    assertCaptureArgs(snapshot.getCaptures().getEntry(), "arg", "java.lang.String", "1");
    assertCaptureArgs(snapshot.getCaptures().getReturn(), "arg", "java.lang.String", "1");
    assertTrue(snapshot.getDuration() > 0);
    assertTrue(snapshot.getStack().size() > 0);
    Assertions.assertEquals("CapturedSnapshot01.main", snapshot.getStack().get(0).getFunction());
  }

  @Test
  public void singleLineProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "main", "int (java.lang.String)", "8");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(8), "arg", "java.lang.String", "1");
    assertCaptureLocals(snapshot.getCaptures().getLines().get(8), "var1", "int", "1");
    assertTrue(snapshot.getStack().size() > 0);
    Assertions.assertEquals("CapturedSnapshot01.java", snapshot.getStack().get(0).getFileName());
  }

  @Test
  public void resolutionFails() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "main", "int (java.lang.String)", "8");
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.init((id, clazz) -> null, null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void resolutionThrows() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe lineProbe = createProbe(PROBE_ID1, CLASS_NAME, "main", "int (java.lang.String)", "8");
    LogProbe methodProbe = createProbe(PROBE_ID2, CLASS_NAME, "main", "int (java.lang.String)");
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, lineProbe, methodProbe);
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.init(
        (id, clazz) -> {
          throw new IllegalArgumentException("oops");
        },
        null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void constructor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(String, Object)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assertions.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void overloadedConstructor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assertions.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void veryOldClassFile() throws Exception {
    final String CLASS_NAME = "antlr.Token"; // compiled with jdk 1.2
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = Class.forName(CLASS_NAME);
    Assertions.assertNotNull(testClass);
    testClass.newInstance();
    assertOneSnapshot(listener);
  }

  @Test
  public void oldJavacBug() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.classfiles.JavacBug"; // compiled with jdk 1.6
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "main", null);
    Class<?> testClass = Class.forName(CLASS_NAME);
    Assertions.assertNotNull(testClass);
    int result = Reflect.on(testClass).call("main", "").get();
    assertEquals(45, result);
    assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void nestedConstructor() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(Throwable)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "init").get();
    Assertions.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void nestedConstructor2() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot13";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(int)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
  }

  @Test
  public void nestedConstructor3() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot14";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(int, int, int)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
  }

  @Test
  public void inheritedConstructor() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot06";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME + "$Inherited", "<init>", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "obj2", "java.lang.Object", (String) null);
    CapturedContext.CapturedValue obj2 = snapshot.getCaptures().getReturn().getFields().get("obj2");
    Map<String, CapturedContext.CapturedValue> fields = getFields(obj2);
    Assertions.assertEquals(24, fields.get("intValue").getValue());
    Assertions.assertEquals(3.14, fields.get("doubleValue").getValue());
  }

  @Test
  public void largeStackInheritedConstructor() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot15";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createProbe(PROBE_ID1, CLASS_NAME, "<init>", "()"),
            createProbe(PROBE_ID2, CLASS_NAME, "<init>", "(String, long, String)"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    long result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(4_000_000_001L, result);
    assertSnapshots(listener, 2, PROBE_ID2, PROBE_ID1);
  }

  @Test
  public void multiMethods() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createProbe(PROBE_ID1, CLASS_NAME, "f1", "(int)"),
            createProbe(PROBE_ID2, CLASS_NAME, "f2", "(int)"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(48, result);
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
    LogProbe probe = createProbe(PROBE_ID1, CLASS_NAME, "f1", "(int)");
    LogProbe probe2 = createProbe(PROBE_ID2, CLASS_NAME, "f1", "(int)");
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, probe, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(48, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    Snapshot snapshot0 = snapshots.get(0);
    assertCaptureArgs(snapshot0.getCaptures().getEntry(), "value", "int", "31");
    assertCaptureReturnValue(snapshot0.getCaptures().getReturn(), "int", "31");
    Snapshot snapshot1 = snapshots.get(1);
    assertCaptureArgs(snapshot1.getCaptures().getEntry(), "value", "int", "31");
    assertCaptureReturnValue(snapshot1.getCaptures().getReturn(), "int", "31");
  }

  private List<Snapshot> assertSnapshots(
      DebuggerTransformerTest.TestSnapshotListener listener,
      int expectedCount,
      ProbeId... probeIds) {
    Assertions.assertEquals(expectedCount, listener.snapshots.size());
    for (int i = 0; i < probeIds.length; i++) {
      Assertions.assertEquals(probeIds[i].getId(), listener.snapshots.get(i).getProbe().getId());
    }
    return listener.snapshots;
  }

  @Test
  public void catchBlock() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "f", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assertions.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  @Disabled("no more support of line range")
  public void insideSynchronizedBlock() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    final int LINE_START = 46;
    final int LINE_END = 48;
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createProbe(
                PROBE_ID, CLASS_NAME, "synchronizedBlock", "(int)", LINE_START + "-" + LINE_END));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "synchronizedBlock").get();
    Assertions.assertEquals(76, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createProbe(
                PROBE_ID, CLASS_NAME, "synchronizedBlock", "(int)", LINE_START + "-" + LINE_END));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "synchronizedBlock").get();
    Assertions.assertEquals(76, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(2, snapshot.getCaptures().getLines().size());
    assertCaptureLocals(snapshot.getCaptures().getLines().get(LINE_START), "count", "int", "31");
    assertCaptureLocals(snapshot.getCaptures().getLines().get(LINE_END), "count", "int", "76");
  }

  @Test
  public void sourceFileProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, CLASS_NAME + ".java", 4));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(4), "value", "int", "31");
  }

  @Test
  public void simpleSourceFileProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, "CapturedSnapshot10.java", 11));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("main", snapshot.getProbe().getLocation().getMethod());
    assertCaptureLocals(snapshot.getCaptures().getLines().get(11), "var1", "int", "1");
  }

  @Test
  public void sourceFileProbeFullPath() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createSourceFileProbe(PROBE_ID, "src/main/java/" + DIR_CLASS_NAME + ".java", 11));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("main", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(11), "arg", "java.lang.String", "2");
  }

  @Test
  public void sourceFileProbeFullPathTopLevelClass() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            "com.datadog.debugger.TopLevel01",
            createSourceFileProbe(PROBE_ID, "src/main/java/" + DIR_CLASS_NAME + ".java", 21));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(42 * 42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(
        "com.datadog.debugger.TopLevel01", snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("process", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(21), "arg", "int", "42");
  }

  @Test
  public void methodProbeLineProbeMix() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot11";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createSourceFileProbe(PROBE_ID1, "src/main/java/" + DIR_CLASS_NAME + ".java", 10),
            createProbe(PROBE_ID2, CLASS_NAME, "main", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(2, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    Snapshot snapshot0 = snapshots.get(0);
    Assertions.assertNull(snapshot0.getCaptures().getEntry());
    Assertions.assertNull(snapshot0.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot0.getCaptures().getLines().size());
    Assertions.assertEquals(
        "com.datadog.debugger.CapturedSnapshot11", snapshot0.getProbe().getLocation().getType());
    Assertions.assertEquals("main", snapshot0.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot0.getCaptures().getLines().get(10), "arg", "java.lang.String", "2");
    assertCaptureLocals(snapshot0.getCaptures().getLines().get(10), "var1", "int", "1");
    Snapshot snapshot1 = snapshots.get(1);
    Assertions.assertEquals(
        "com.datadog.debugger.CapturedSnapshot11", snapshot1.getProbe().getLocation().getType());
    Assertions.assertEquals("main", snapshot1.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot1.getCaptures().getEntry(), "arg", "java.lang.String", "2");
    assertCaptureReturnValue(snapshot1.getCaptures().getReturn(), "int", "2");
  }

  @Test
  public void sourceFileProbeScala() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot101";
    final String FILE_NAME = CLASS_NAME + ".scala";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, FILE_NAME, 3));
    String source = getFixtureContent("/" + FILE_NAME);
    Class<?> testClass = ScalaHelper.compileAndLoad(source, CLASS_NAME, FILE_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(3), "value", "int", "31");
  }

  @Test
  public void sourceFileProbeGroovy() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot201";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, CLASS_NAME + ".groovy", 4));
    String source = getFixtureContent("/" + CLASS_NAME + ".groovy");
    GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
    Class<?> testClass = groovyClassLoader.parseClass(source);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(4), "value", "int", "31");
  }

  @Test
  public void sourceFileProbeKotlin() {
    final String CLASS_NAME = "CapturedSnapshot301";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, CLASS_NAME + ".kt", 4));
    URL resource = CapturedSnapshotTest.class.getResource("/" + CLASS_NAME + ".kt");
    Assertions.assertNotNull(resource);
    List<File> filesToDelete = new ArrayList<>();
    Class<?> testClass = KotlinHelper.compileAndLoad(CLASS_NAME, resource.getFile(), filesToDelete);
    try {
      Object companion = Reflect.on(testClass).get("Companion");
      int result = Reflect.on(companion).call("main", "").get();
      Assertions.assertEquals(48, result);
      Snapshot snapshot = assertOneSnapshot(listener);
      Assertions.assertNull(snapshot.getCaptures().getEntry());
      Assertions.assertNull(snapshot.getCaptures().getReturn());
      Assertions.assertEquals(1, snapshot.getCaptures().getLines().size());
      Assertions.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
      Assertions.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
      assertCaptureArgs(snapshot.getCaptures().getLines().get(4), "value", "int", "31");
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe, compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue returnValue =
        snapshot.getCaptures().getReturn().getLocals().get("@return");
    Map<String, CapturedContext.CapturedValue> fields = getFields(returnValue);
    assertTrue(fields.containsKey("nullsd"));
    assertTrue(fields.containsKey("l1"));
    CapturedContext.CapturedValue s1 = fields.get("s1");
    Map<String, CapturedContext.CapturedValue> s1Fields =
        (Map<String, CapturedContext.CapturedValue>) s1.getValue();
    Assertions.assertEquals("101", String.valueOf(s1Fields.get("intValue").getValue()));
    Assertions.assertEquals("foo1", s1Fields.get("strValue").getValue());
    Assertions.assertEquals("null", String.valueOf(s1Fields.get("listValue").getValue()));
    Assertions.assertEquals(
        DEPTH_REASON, String.valueOf(s1Fields.get("listValue").getNotCapturedReason()));
  }

  @Test
  public void fieldExtractorLength() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(1, 100, 2, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, CapturedContext.CapturedValue> fields = getFields(simpleData);
    Assertions.assertEquals(1, fields.size());
    Assertions.assertEquals(
        DEPTH_REASON, fields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
  }

  @Test
  public void fieldExtractorDepth0() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(0, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, CapturedContext.CapturedValue> simpleDataFields = getFields(simpleData);
    Assertions.assertEquals(1, simpleDataFields.size());
    Assertions.assertEquals(
        DEPTH_REASON, simpleDataFields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
  }

  @Test
  public void fieldExtractorDepth1() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder = createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    LogProbe simpleDataProbe = builder.capture(1, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, CapturedContext.CapturedValue> simpleDataFields = getFields(simpleData);
    Assertions.assertEquals(4, simpleDataFields.size());
    Assertions.assertEquals("foo", simpleDataFields.get("strValue").getValue());
    Assertions.assertEquals(42, simpleDataFields.get("intValue").getValue());
    Assertions.assertEquals(DEPTH_REASON, simpleDataFields.get("listValue").getNotCapturedReason());
  }

  @Test
  public void fieldExtractorCount2() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    LogProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createCompositeData", "()");
    LogProbe compositeDataProbe = builder.capture(2, 3, 255, 2).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext.CapturedValue returnValue =
        snapshot.getCaptures().getReturn().getLocals().get("@return");
    Assertions.assertEquals("CapturedSnapshot04$CompositeData", returnValue.getType());
    Map<String, CapturedContext.CapturedValue> fields = getFields(returnValue);
    Assertions.assertEquals(3, fields.size());
    Assertions.assertEquals(
        FIELD_COUNT_REASON, fields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
    Map<String, CapturedContext.CapturedValue> s1Fields =
        (Map<String, CapturedContext.CapturedValue>) fields.get("s1").getValue();
    Assertions.assertEquals("foo1", s1Fields.get("strValue").getValue());
    Assertions.assertEquals(101, s1Fields.get("intValue").getValue());
    Map<String, CapturedContext.CapturedValue> s2Fields =
        (Map<String, CapturedContext.CapturedValue>) fields.get("s2").getValue();
    Assertions.assertEquals("foo2", s2Fields.get("strValue").getValue());
    Assertions.assertEquals(202, s2Fields.get("intValue").getValue());

    CapturedContext.CapturedValue compositeData =
        snapshot.getCaptures().getReturn().getLocals().get("compositeData");
    Map<String, CapturedContext.CapturedValue> compositeDataFields = getFields(compositeData);
    Assertions.assertEquals(3, compositeDataFields.size());
    Assertions.assertEquals(
        FIELD_COUNT_REASON,
        compositeDataFields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
    assertTrue(compositeDataFields.containsKey("s1"));
    assertTrue(compositeDataFields.containsKey("s2"));
  }

  @Test
  public void uncaughtException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "triggerUncaughtException", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.on(testClass).call("main", "triggerUncaughtException").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      Assertions.assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureThrowable(
        snapshot.getCaptures().getReturn(),
        "java.lang.IllegalStateException",
        "oops",
        "CapturedSnapshot05.triggerUncaughtException",
        7);
  }

  @Test
  public void caughtException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "triggerCaughtException", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "triggerCaughtException").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureThrowable(
        snapshot.getCaptures().getCaughtExceptions().get(0),
        "java.lang.IllegalStateException",
        "oops",
        "CapturedSnapshot05.triggerCaughtException",
        12);
  }

  @Test
  public void rateLimitSnapshot() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe logProbes =
        new LogProbe.Builder()
            .language(LANGUAGE)
            .probeId(PROBE_ID)
            .where(CLASS_NAME, 8)
            .sampling(new LogProbe.Sampling(1))
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.on(testClass).call("main", "1").get();
      Assertions.assertEquals(3, result);
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
            .addLogProbes(Arrays.asList(probe1, probe2))
            .add(new LogProbe.Sampling(1))
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, config);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.on(testClass).call("main", "").get();
      Assertions.assertEquals(48, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.on(testClass).call("main", String.valueOf(i)).get();
      assertTrue((i == 2 && result == 2) || result == 3);
    }
    Assertions.assertEquals(1, listener.snapshots.size());
    assertCaptureArgs(
        listener.snapshots.get(0).getCaptures().getReturn(), "arg", "java.lang.String", "5");
  }

  @Test
  public void staticFieldCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot19";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(DSL.eq(DSL.ref("strField"), DSL.value("foo"))), "strField == 'foo'"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "0").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, CapturedContext.CapturedValue> staticFields =
        snapshot.getCaptures().getReturn().getStaticFields();
    assertEquals(4, staticFields.size());
    assertEquals("foo", getValue(staticFields.get("strField")));
    assertEquals("1001", getValue(staticFields.get("intField")));
    assertEquals(String.valueOf(Math.PI), getValue(staticFields.get("doubleField")));
    assertTrue(staticFields.containsKey("intArrayField"));
  }

  @Test
  public void simpleFalseConditionTest() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    LogProbe logProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)", "35")
            .when(
                new ProbeCondition(DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("5"))), "arg == '5'"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "0").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(0, listener.snapshots.size());
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
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(1, listener.snapshots.size());
    List<EvaluationError> evaluationErrors = listener.snapshots.get(0).getEvaluationErrors();
    Assertions.assertEquals(1, evaluationErrors.size());
    Assertions.assertEquals("fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals(
        "Cannot dereference to field: fld", evaluationErrors.get(0).getMessage());
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(expectedSnapshots, listener.snapshots.size());
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
    Assertions.assertEquals("fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals(
        "Cannot dereference to field: fld", evaluationErrors.get(0).getMessage());
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
    Assertions.assertEquals("fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals(
        "Cannot dereference to field: fld", evaluationErrors.get(0).getMessage());
    Assertions.assertNull(snapshots.get(1).getEvaluationErrors());
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
    Assertions.assertEquals("fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals(
        "Cannot dereference to field: fld", evaluationErrors.get(0).getMessage());
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
    Assertions.assertNull(snapshots.get(0).getEvaluationErrors());
    List<EvaluationError> evaluationErrors = snapshots.get(1).getEvaluationErrors();
    Assertions.assertEquals(1, evaluationErrors.size());
    Assertions.assertEquals("fld", evaluationErrors.get(0).getExpr());
    Assertions.assertEquals(
        "Cannot dereference to field: fld", evaluationErrors.get(0).getMessage());
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(2, listener.snapshots.size());
    Assertions.assertNull(listener.snapshots.get(0).getEvaluationErrors());
    Assertions.assertNull(listener.snapshots.get(1).getEvaluationErrors());
  }

  @Test
  public void fields() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "f", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assertions.assertEquals(42, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(INHERITED_CLASS_NAME, probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "inherited").get();
    Assertions.assertEquals(42, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    long result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(4_000_000_001L, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(INHERITED_CLASS_NAME, logProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "inherited").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, CapturedContext.CapturedValue> staticFields =
        snapshot.getCaptures().getReturn().getStaticFields();
    assertEquals(5, staticFields.size());
    assertEquals("barfoo", getValue(staticFields.get("strValue")));
    assertEquals("48", getValue(staticFields.get("intValue")));
    assertEquals("6.28", getValue(staticFields.get("doubleValue")));
  }

  @Test
  public void staticLambda() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot07";
    CorrelationAccess spyCorrelationAccess = spy(CorrelationAccess.instance());
    setCorrelationSingleton(spyCorrelationAccess);
    doReturn(true).when(spyCorrelationAccess).isAvailable();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, null, null, "33"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "static", "email@address").get();
    Assertions.assertEquals(8, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext context = snapshot.getCaptures().getLines().get(33);
    Assertions.assertNotNull(context);
    assertCaptureLocals(context, "idx", "int", "5");
  }

  @Test
  public void capturingLambda() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot07";
    CorrelationAccess spyCorrelationAccess = spy(CorrelationAccess.instance());
    setCorrelationSingleton(spyCorrelationAccess);
    doReturn(true).when(spyCorrelationAccess).isAvailable();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, null, null, "44"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "capturing", "email@address").get();
    Assertions.assertEquals(8, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    CapturedContext context = snapshot.getCaptures().getLines().get(44);
    Assertions.assertNotNull(context);
    assertCaptureLocals(context, "idx", "int", "5");
    assertCaptureFields(context, "strValue", "java.lang.String", "email@address");
  }

  @Test
  public void tracerInstrumentedClass() throws Exception {
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    final String CLASS_NAME = "com.datadog.debugger.jaxrs.MyResource";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "createResource", null);
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
    Assertions.assertEquals(4, arguments.size());
    assertTrue(arguments.containsKey("this"));
    assertTrue(arguments.containsKey("apiKey"));
    assertTrue(arguments.containsKey("uriInfo"));
    assertTrue(arguments.containsKey("value"));
  }

  @Test
  public void noCodeMethods() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot09";
    LogProbe nativeMethodProbe = createProbe(PROBE_ID1, CLASS_NAME, "nativeMethod", "()");
    LogProbe abstractMethodProbe = createProbe(PROBE_ID2, CLASS_NAME, "abstractMethod", "()");
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, nativeMethodProbe, abstractMethodProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(1, result);
    ArgumentCaptor<ProbeId> probeIdCaptor = ArgumentCaptor.forClass(ProbeId.class);
    ArgumentCaptor<String> strCaptor = ArgumentCaptor.forClass(String.class);
    verify(probeStatusSink, times(2)).addError(probeIdCaptor.capture(), strCaptor.capture());
    Assertions.assertEquals(PROBE_ID1.getId(), probeIdCaptor.getAllValues().get(0).getId());
    Assertions.assertEquals(
        "Cannot instrument an abstract or native method", strCaptor.getAllValues().get(0));
    Assertions.assertEquals(PROBE_ID2.getId(), probeIdCaptor.getAllValues().get(1).getId());
    Assertions.assertEquals(
        "Cannot instrument an abstract or native method", strCaptor.getAllValues().get(1));
  }

  @Test
  public void duplicateClassDefinition() throws Exception {
    // this test reproduces a very specific case where we get:
    // java.lang.LinkageError: loader utils.MemClassLoader @1f7ef9ea
    // attempted duplicate class definition for com.datadog.debugger.CapturedSnapshot12.
    // (com.datadog.debugger.CapturedSnapshot12 is in unnamed module of loader utils.MemClassLoader
    // @1f7ef9ea, parent loader 'app')
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot12";
    LogProbe abstractMethodProbe = createProbe(PROBE_ID, CLASS_NAME, "<init>", null);
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, abstractMethodProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Assertions.assertNotNull(testClass);
  }

  @Test
  public void overloadedMethods() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot16";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "overload", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(63, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 4, PROBE_ID, PROBE_ID, PROBE_ID, PROBE_ID);
    assertCaptureReturnValue(snapshots.get(0).getCaptures().getReturn(), "int", "42");
    assertCaptureArgs(snapshots.get(1).getCaptures().getEntry(), "s", "java.lang.String", "1");
    assertCaptureArgs(snapshots.get(2).getCaptures().getEntry(), "s", "java.lang.String", "2");
    assertCaptureArgs(snapshots.get(3).getCaptures().getEntry(), "s", "java.lang.String", "3");
  }

  @Test
  public void noDebugInfoEmptyMethod() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot03";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "empty", null));
    Map<String, byte[]> classFileBuffers = compile(CLASS_NAME, SourceCompiler.DebugInfo.NONE);
    Class<?> testClass = loadClass(CLASS_NAME, classFileBuffers);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(48, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void instrumentTheWorld() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot01";
    Map<String, byte[]> classFileBuffers = compile(CLASS_NAME);
    DebuggerTransformerTest.TestSnapshotListener listener =
        setupInstrumentTheWorldTransformer(null);
    Class<?> testClass;
    try {
      testClass = loadClass(CLASS_NAME, classFileBuffers);
    } finally {
      instr.removeTransformer(currentTransformer);
    }
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(2, result);
    Assertions.assertEquals(1, listener.snapshots.size());
    ProbeImplementation probeImplementation = listener.snapshots.get(0).getProbe();
    assertTrue(probeImplementation.isCaptureSnapshot());
    assertEquals("main", probeImplementation.getLocation().getMethod());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/exclude-files/singleClass.txt", "/exclude-files/startsWithClass.txt"})
  public void instrumentTheWorld_excludeClass(String excludeFileName) throws Exception {
    final String CLASS_NAME = "CapturedSnapshot01";
    Map<String, byte[]> classFileBuffers = compile(CLASS_NAME);
    URL resource = getClass().getResource(excludeFileName);
    DebuggerTransformerTest.TestSnapshotListener listener =
        setupInstrumentTheWorldTransformer(resource.getPath());
    Class<?> testClass;
    try {
      testClass = loadClass(CLASS_NAME, classFileBuffers);
    } finally {
      instr.removeTransformer(currentTransformer);
    }
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(2, result);
    Assertions.assertEquals(0, listener.snapshots.size());
  }

  @Test
  public void objectDynamicType() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot17";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "processWithArg", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(50, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, null, null, "14"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Map<String, String> expectedFields = new HashMap<>();
    expectedFields.put("detailMessage", "For input string: \"a\"");
    assertCaptureLocals(
        snapshot.getCaptures().getLines().get(14),
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
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(0, listener.snapshots.size());
    assertTrue(listener.skipped);
    Assertions.assertEquals(DebuggerContext.SkipCause.CONDITION, listener.cause);
  }

  @Test
  public void uncaughtExceptionConditionLocalVar() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    LogProbe probe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "main", "(String)")
            .when(new ProbeCondition(DSL.when(DSL.gt(DSL.ref("after"), DSL.value(0))), "after > 0"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.on(testClass).call("main", "triggerUncaughtException").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      Assertions.assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureThrowable(
        snapshot.getCaptures().getReturn(),
        "java.lang.IllegalStateException",
        "oops",
        "CapturedSnapshot05.triggerUncaughtException",
        7);
    Assertions.assertEquals(2, snapshot.getEvaluationErrors().size());
    Assertions.assertEquals(
        "Cannot find symbol: after", snapshot.getEvaluationErrors().get(0).getMessage());
    Assertions.assertEquals(
        "java.lang.IllegalStateException: oops",
        snapshot.getEvaluationErrors().get(1).getMessage());
  }

  @Test
  public void enumConstructorArgs() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot23";
    final String ENUM_CLASS = CLASS_NAME + "$MyEnum";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(ENUM_CLASS, createProbe(PROBE_ID, ENUM_CLASS, "<init>", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(2, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "convert", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assertions.assertEquals(2, result);
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
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(INNER_CLASS, createProbe(PROBE_ID, INNER_CLASS, "size", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(1, result);
  }

  @Test
  public void recursiveCaptureException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot24";
    final String INNER_CLASS = CLASS_NAME + "$HolderWithException";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(INNER_CLASS, createProbe(PROBE_ID, INNER_CLASS, "size", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.on(testClass).call("main", "exception").get();
      Assertions.fail("should not reach this code");
    } catch (ReflectException ex) {
      Assertions.assertEquals("not supported", ex.getCause().getCause().getMessage());
    }
  }

  @Test
  public void beforeForLoopLineProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, null, null, "46");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "synchronizedBlock").get();
    Assertions.assertEquals(76, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureLocals(snapshot.getCaptures().getLines().get(46), "count", "int", "31");
  }

  @Test
  public void dupLineProbeSameTemplate() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    final String LOG_TEMPLATE = "msg1={typed.fld.fld.msg}";
    LogProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, null, null, "39")
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .build();
    LogProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, null, null, "39")
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, probe1, probe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    for (Snapshot snapshot : snapshots) {
      assertEquals("msg1=hello", snapshot.getMessage());
    }
  }

  private DebuggerTransformerTest.TestSnapshotListener setupInstrumentTheWorldTransformer(
      String excludeFileName) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerInstrumentTheWorld()).thenReturn(true);
    when(config.getDebuggerExcludeFiles()).thenReturn(excludeFileName);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    DebuggerTransformerTest.TestSnapshotListener listener =
        new DebuggerTransformerTest.TestSnapshotListener();
    DebuggerAgentHelper.injectSink(listener);
    currentTransformer =
        DebuggerAgent.setupInstrumentTheWorldTransformer(
            config, instr, new DebuggerSink(config), null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    return listener;
  }

  private void setCorrelationSingleton(Object instance) {
    Class<?> singletonClass = CorrelationAccess.class.getDeclaredClasses()[0];
    try {
      Field instanceField = singletonClass.getDeclaredField("INSTANCE");
      instanceField.setAccessible(true);
      Field modifiersField = Field.class.getDeclaredField("modifiers");
      modifiersField.setAccessible(true);
      modifiersField.setInt(instanceField, instanceField.getModifiers() & ~Modifier.FINAL);
      instanceField.set(null, instance);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      e.printStackTrace();
    }
  }

  private Snapshot assertOneSnapshot(DebuggerTransformerTest.TestSnapshotListener listener) {
    Assertions.assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    Assertions.assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
    return snapshot;
  }

  private DebuggerTransformerTest.TestSnapshotListener installSingleProbe(
      String typeName, String methodName, String signature, String... lines) {
    LogProbe logProbes = createProbe(PROBE_ID, typeName, methodName, signature, lines);
    return installProbes(typeName, logProbes);
  }

  private DebuggerTransformerTest.TestSnapshotListener installProbes(
      String expectedClassName, Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    Collection<LogProbe> logProbes = configuration.getLogProbes();
    probeStatusSink = mock(ProbeStatusSink.class);
    currentTransformer =
        new DebuggerTransformer(
            config, configuration, null, new DebuggerSink(config, probeStatusSink));
    instr.addTransformer(currentTransformer);
    DebuggerTransformerTest.TestSnapshotListener listener =
        new DebuggerTransformerTest.TestSnapshotListener();
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.init(
        (id, callingClass) -> resolver(id, callingClass, expectedClassName, logProbes), null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());
    for (LogProbe probe : logProbes) {
      if (probe.getSampling() != null) {
        ProbeRateLimiter.setRate(
            probe.getId(), probe.getSampling().getSnapshotsPerSecond(), probe.isCaptureSnapshot());
      }
    }
    if (configuration.getSampling() != null) {
      ProbeRateLimiter.setGlobalSnapshotRate(configuration.getSampling().getSnapshotsPerSecond());
    }
    return listener;
  }

  private ProbeImplementation resolver(
      String id, Class<?> callingClass, String expectedClassName, Collection<LogProbe> logProbes) {
    Assertions.assertEquals(expectedClassName, callingClass.getName());
    for (LogProbe probe : logProbes) {
      if (probe.getId().equals(id)) {
        return probe;
      }
    }
    return null;
  }

  private DebuggerTransformerTest.TestSnapshotListener installProbes(
      String expectedClassName, LogProbe... logProbes) {
    return installProbes(
        expectedClassName,
        Configuration.builder()
            .setService(SERVICE_NAME)
            .addLogProbes(Arrays.asList(logProbes))
            .build());
  }

  private void assertCaptureArgs(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue capturedValue = context.getArguments().get(name);
    Assertions.assertEquals(typeName, capturedValue.getType());
    Assertions.assertEquals(value, getValue(capturedValue));
  }

  private void assertCaptureLocals(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue localVar = context.getLocals().get(name);
    Assertions.assertEquals(typeName, localVar.getType());
    Assertions.assertEquals(value, getValue(localVar));
  }

  private void assertCaptureLocals(
      CapturedContext context, String name, String typeName, Map<String, String> expectedFields) {
    CapturedContext.CapturedValue localVar = context.getLocals().get(name);
    Assertions.assertEquals(typeName, localVar.getType());
    Map<String, CapturedContext.CapturedValue> fields = getFields(localVar);
    for (Map.Entry<String, String> entry : expectedFields.entrySet()) {
      assertTrue(fields.containsKey(entry.getKey()));
      CapturedContext.CapturedValue fieldCapturedValue = fields.get(entry.getKey());
      if (fieldCapturedValue.getNotCapturedReason() != null) {
        Assertions.assertEquals(
            entry.getValue(), String.valueOf(fieldCapturedValue.getNotCapturedReason()));
      } else {
        Assertions.assertEquals(entry.getValue(), String.valueOf(fieldCapturedValue.getValue()));
      }
    }
  }

  private void assertCaptureFields(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue field = context.getFields().get(name);
    Assertions.assertEquals(typeName, field.getType());
    Assertions.assertEquals(value, getValue(field));
  }

  private void assertCaptureFields(
      CapturedContext context, String name, String typeName, Collection<?> collection) {
    CapturedContext.CapturedValue field = context.getFields().get(name);
    Assertions.assertEquals(typeName, field.getType());
    Iterator<?> iterator = collection.iterator();
    for (Object obj : getCollection(field)) {
      if (iterator.hasNext()) {
        Assertions.assertEquals(iterator.next(), obj);
      } else {
        Assertions.fail("not same number of elements");
      }
    }
  }

  private void assertCaptureFields(
      CapturedContext context, String name, String typeName, Map<Object, Object> expectedMap) {
    CapturedContext.CapturedValue field = context.getFields().get(name);
    Assertions.assertEquals(typeName, field.getType());
    Map<Object, Object> map = getMap(field);
    Assertions.assertEquals(expectedMap.size(), map.size());
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      assertTrue(expectedMap.containsKey(entry.getKey()));
      Assertions.assertEquals(expectedMap.get(entry.getKey()), entry.getValue());
    }
  }

  private void assertCaptureFieldCount(CapturedContext context, int expectedFieldCount) {
    Assertions.assertEquals(expectedFieldCount, context.getFields().size());
  }

  private void assertCaptureReturnValue(CapturedContext context, String typeName, String value) {
    CapturedContext.CapturedValue returnValue = context.getLocals().get("@return");
    Assertions.assertEquals(typeName, returnValue.getType());
    Assertions.assertEquals(value, getValue(returnValue));
  }

  private void assertCaptureReturnValue(
      CapturedContext context, String typeName, Map<String, String> expectedFields) {
    CapturedContext.CapturedValue returnValue = context.getLocals().get("@return");
    Assertions.assertEquals(typeName, returnValue.getType());
    Map<String, CapturedContext.CapturedValue> fields = getFields(returnValue);
    for (Map.Entry<String, String> entry : expectedFields.entrySet()) {
      assertTrue(fields.containsKey(entry.getKey()));
      CapturedContext.CapturedValue fieldCapturedValue = fields.get(entry.getKey());
      if (fieldCapturedValue.getNotCapturedReason() != null) {
        Assertions.assertEquals(
            entry.getValue(), String.valueOf(fieldCapturedValue.getNotCapturedReason()));
      } else {
        Assertions.assertEquals(entry.getValue(), String.valueOf(fieldCapturedValue.getValue()));
      }
    }
  }

  private void assertCaptureThrowable(
      CapturedContext context, String typeName, String message, String methodName, int lineNumber) {
    CapturedContext.CapturedThrowable throwable = context.getThrowable();
    assertCaptureThrowable(throwable, typeName, message, methodName, lineNumber);
  }

  private void assertCaptureThrowable(
      CapturedContext.CapturedThrowable throwable,
      String typeName,
      String message,
      String methodName,
      int lineNumber) {
    Assertions.assertNotNull(throwable);
    Assertions.assertEquals(typeName, throwable.getType());
    Assertions.assertEquals(message, throwable.getMessage());
    Assertions.assertNotNull(throwable.getStacktrace());
    Assertions.assertFalse(throwable.getStacktrace().isEmpty());
    Assertions.assertEquals(methodName, throwable.getStacktrace().get(0).getFunction());
    Assertions.assertEquals(lineNumber, throwable.getStacktrace().get(0).getLineNumber());
  }

  private static String getValue(CapturedContext.CapturedValue capturedValue) {
    CapturedContext.CapturedValue valued = null;
    try {
      valued = VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
      if (valued.getNotCapturedReason() != null) {
        Assertions.fail("NotCapturedReason: " + valued.getNotCapturedReason());
      }
      Object obj = valued.getValue();
      return obj != null ? String.valueOf(obj) : null;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static Map<String, CapturedContext.CapturedValue> getFields(
      CapturedContext.CapturedValue capturedValue) {
    try {
      CapturedContext.CapturedValue valued = VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
      Map<String, CapturedContext.CapturedValue> results = new HashMap<>();
      if (valued.getNotCapturedReason() != null) {
        results.put(
            "@" + NOT_CAPTURED_REASON,
            CapturedContext.CapturedValue.notCapturedReason(
                null, null, valued.getNotCapturedReason()));
      }
      if (valued.getValue() == null) {
        return results;
      }
      results.putAll((Map<String, CapturedContext.CapturedValue>) valued.getValue());
      return results;
    } catch (IOException e) {
      e.printStackTrace();
      return Collections.emptyMap();
    }
  }

  private static Collection<?> getCollection(CapturedContext.CapturedValue capturedValue) {
    try {
      Map<String, Object> capturedValueMap = GENERIC_ADAPTER.fromJson(capturedValue.getStrValue());
      List<Object> elements = (List<Object>) capturedValueMap.get("elements");
      if (elements == null) {
        Assertions.fail("not a collection");
      }
      List<Object> result = new ArrayList<>();
      for (Object obj : elements) {
        Map<String, Object> element = (Map<String, Object>) obj;
        String type = (String) element.get("type");
        if (type == null) {
          Assertions.fail("no type for element");
        }
        if (SerializerWithLimits.isPrimitive(type)) {
          result.add(element.get("value"));
        } else {
          Assertions.fail("not implemented");
        }
      }
      return result;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  private Map<Object, Object> getMap(CapturedContext.CapturedValue capturedValue) {
    try {
      Map<String, Object> capturedValueMap = GENERIC_ADAPTER.fromJson(capturedValue.getStrValue());
      List<Object> entries = (List<Object>) capturedValueMap.get("entries");
      if (entries == null) {
        Assertions.fail("not a map");
      }
      Map<Object, Object> result = new HashMap<>();
      for (Object obj : entries) {
        List<Object> entry = (List<Object>) obj;
        Map<String, Object> key = (Map<String, Object>) entry.get(0);
        Map<String, Object> value = (Map<String, Object>) entry.get(1);
        result.put(key.get("value"), value.get("value"));
      }
      return result;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  private static LogProbe createProbe(
      ProbeId id, String typeName, String methodName, String signature, String... lines) {
    return createProbeBuilder(id, typeName, methodName, signature, lines).build();
  }

  private static LogProbe.Builder createProbeBuilder(
      ProbeId id, String typeName, String methodName, String signature, String... lines) {
    return LogProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .captureSnapshot(true)
        .where(typeName, methodName, signature, lines)
        // Increase sampling limit to avoid being sampled during tests
        .sampling(new LogProbe.Sampling(100));
  }

  private static LogProbe createSourceFileProbe(ProbeId id, String sourceFile, int line) {
    return new LogProbe.Builder()
        .language(LANGUAGE)
        .probeId(id)
        .captureSnapshot(true)
        .where(null, null, null, line, sourceFile)
        .build();
  }

  static class KotlinHelper {
    public static Class<?> compileAndLoad(
        String className, String sourceFileName, List<File> outputFilesToDelete) {
      K2JVMCompiler compiler = new K2JVMCompiler();
      K2JVMCompilerArguments args = compiler.createArguments();
      args.setFreeArgs(Collections.singletonList(sourceFileName));
      String compilerOutputDir = "/tmp/" + CapturedSnapshotTest.class.getSimpleName() + "-kotlin";
      args.setDestination(compilerOutputDir);
      args.setClasspath(System.getProperty("java.class.path"));
      ExitCode exitCode =
          compiler.execImpl(
              new PrintingMessageCollector(System.out, MessageRenderer.WITHOUT_PATHS, true),
              Services.EMPTY,
              args);
      if (exitCode.getCode() != 0) {
        throw new RuntimeException("Kotlin compilation failed");
      }
      File compileOutputDirFile = new File(compilerOutputDir);
      try {
        URLClassLoader urlClassLoader =
            new URLClassLoader(new URL[] {compileOutputDirFile.toURI().toURL()});
        return urlClassLoader.loadClass(className);
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      } finally {
        registerFilesToDeleteDir(compileOutputDirFile, outputFilesToDelete);
      }
    }

    public static void registerFilesToDeleteDir(File dir, List<File> outputFilesToDelete) {
      if (!dir.exists()) {
        return;
      }
      try {
        Files.walk(dir.toPath())
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(outputFilesToDelete::add);
      } catch (IOException ex) {
        ex.printStackTrace();
      }
    }
  }
}
