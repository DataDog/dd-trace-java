package com.datadog.debugger.agent;

import static com.datadog.debugger.util.MoshiSnapshotHelper.DEPTH_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.FIELD_COUNT_REASON;
import static com.datadog.debugger.util.MoshiSnapshotHelper.NOT_CAPTURED_REASON;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compile;
import static utils.InstrumentationTestHelper.compileAndLoadClass;
import static utils.InstrumentationTestHelper.loadClass;
import static utils.TestHelper.getFixtureContent;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CorrelationAccess;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.Snapshot;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;
import org.joor.Reflect;
import org.joor.ReflectException;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import utils.SourceCompiler;

public class CapturedSnapshotTest {
  private static final String LANGUAGE = "java";
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String PROBE_ID1 = "beae1807-f3b0-4ea8-a74f-826790c5e6f6";
  private static final String PROBE_ID2 = "beae1807-f3b0-4ea8-a74f-826790c5e6f7";
  private static final long ORG_ID = 2;
  private static final String SERVICE_NAME = "service-name";
  private static final JsonAdapter<Snapshot.CapturedValue> VALUE_ADAPTER =
      new MoshiSnapshotHelper.CapturedValueAdapter();
  private static final JsonAdapter<Map<String, Object>> GENERIC_ADAPTER =
      MoshiHelper.createGenericAdapter();

  private Instrumentation instr = ByteBuddyAgent.install();
  private ClassFileTransformer currentTransformer;

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
    ProbeRateLimiter.resetGlobalRate();
  }

  @Test
  public void methodNotFound() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "foobar", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assert.assertEquals(2, result);
    Assert.assertEquals(
        "Cannot find CapturedSnapshot01::foobar",
        listener.errors.get(PROBE_ID).get(0).getMessage());
  }

  @Test
  public void lineNotFound() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, CLASS_NAME + ".java", "42"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assert.assertEquals(2, result);
    Assert.assertEquals(
        "Cannot find CapturedSnapshot01:L42", listener.errors.get(PROBE_ID).get(0).getMessage());
  }

  @Test
  public void methodProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNotNull(snapshot.getCaptures().getEntry());
    Assert.assertNotNull(snapshot.getCaptures().getReturn());
    assertCaptureArgs(snapshot.getCaptures().getEntry(), "arg", "java.lang.String", "1");
    assertCaptureArgs(snapshot.getCaptures().getReturn(), "arg", "java.lang.String", "1");
    Assert.assertTrue(snapshot.retrieveDuration() > 0);
  }

  @Test
  public void singleLineProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "main", "int (java.lang.String)", "8");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(8), "arg", "java.lang.String", "1");
    assertCaptureLocals(snapshot.getCaptures().getLines().get(8), "var1", "int", "1");
  }

  @Test
  public void resolutionFails() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "main", "int (java.lang.String)", "8");
    DebuggerContext.init(listener, (id, clazz) -> null, null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    Assert.assertEquals(Snapshot.ProbeDetails.UNKNOWN.getId(), snapshot.getProbe().getId());
  }

  @Test
  public void constructor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(String, Object)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assert.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void overloadedConstructor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assert.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void veryOldClassFile() throws Exception {
    final String CLASS_NAME = "antlr.Token"; // compiled with jdk 1.2
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "()");
    Class<?> testClass = Class.forName(CLASS_NAME);
    Assert.assertNotNull(testClass);
    testClass.newInstance();
    assertOneSnapshot(listener);
  }

  @Test
  public void nestedConstructor() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot02";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(Throwable)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "init").get();
    Assert.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
  public void nestedConstructor2() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot13";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(int)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
  }

  @Test
  public void nestedConstructor3() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot14";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME, "<init>", "(int, int, int)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
  }

  @Test
  public void inheritedConstructor() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot06";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(CLASS_NAME + "$Inherited", "<init>", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "obj2", "java.lang.Object", (String) null);
    Snapshot.CapturedValue obj2 = snapshot.getCaptures().getReturn().getFields().get("obj2");
    Map<String, Snapshot.CapturedValue> fields = getFields(obj2);
    Assert.assertEquals(24, fields.get("intValue").getValue());
    Assert.assertEquals(3.14, fields.get("doubleValue").getValue());
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
    Assert.assertEquals(4_000_000_001L, result);
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
    Assert.assertEquals(48, result);
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
    SnapshotProbe probe = createProbe(PROBE_ID1, CLASS_NAME, "f1", "(int)");
    SnapshotProbe probe2 = createProbe(PROBE_ID2, CLASS_NAME, "f1", "(int)");
    probe.addAdditionalProbe(probe2);
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(48, result);
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
      String... probeIds) {
    Assert.assertEquals(expectedCount, listener.snapshots.size());
    for (int i = 0; i < probeIds.length; i++) {
      Assert.assertEquals(probeIds[i], listener.snapshots.get(i).getProbe().getId());
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
    Assert.assertEquals(42, result);
    assertOneSnapshot(listener);
  }

  @Test
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
    Assert.assertEquals(76, result);
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
    Assert.assertEquals(76, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(2, snapshot.getCaptures().getLines().size());
    assertCaptureLocals(snapshot.getCaptures().getLines().get(LINE_START), "count", "int", "31");
    assertCaptureLocals(snapshot.getCaptures().getLines().get(LINE_END), "count", "int", "76");
  }

  @Test
  public void sourceFileProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, CLASS_NAME + ".java", "4"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assert.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assert.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(4), "value", "int", "31");
  }

  @Test
  public void simpleSourceFileProbe() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, "CapturedSnapshot10.java", "11"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assert.assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assert.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assert.assertEquals("main", snapshot.getProbe().getLocation().getMethod());
    assertCaptureLocals(snapshot.getCaptures().getLines().get(11), "var1", "int", "1");
  }

  @Test
  public void sourceFileProbeFullPath() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createSourceFileProbe(PROBE_ID, "src/main/java/" + DIR_CLASS_NAME + ".java", "11"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assert.assertEquals(2, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assert.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assert.assertEquals("main", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(11), "arg", "java.lang.String", "2");
  }

  @Test
  public void sourceFileProbeFullPathTopLevelClass() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            "com.datadog.debugger.TopLevel01",
            createSourceFileProbe(PROBE_ID, "src/main/java/" + DIR_CLASS_NAME + ".java", "21"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(42 * 42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assert.assertEquals(
        "com.datadog.debugger.TopLevel01", snapshot.getProbe().getLocation().getType());
    Assert.assertEquals("process", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(21), "arg", "int", "42");
  }

  @Test
  public void methodProbeLineProbeMix() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot11";
    String DIR_CLASS_NAME = CLASS_NAME.replace('.', '/');
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(
            CLASS_NAME,
            createSourceFileProbe(PROBE_ID1, "src/main/java/" + DIR_CLASS_NAME + ".java", "10"),
            createProbe(PROBE_ID2, CLASS_NAME, "main", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "2").get();
    Assert.assertEquals(2, result);
    List<Snapshot> snapshots = assertSnapshots(listener, 2, PROBE_ID1, PROBE_ID2);
    Snapshot snapshot0 = snapshots.get(0);
    Assert.assertNull(snapshot0.getCaptures().getEntry());
    Assert.assertNull(snapshot0.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot0.getCaptures().getLines().size());
    Assert.assertEquals(
        "com.datadog.debugger.CapturedSnapshot11", snapshot0.getProbe().getLocation().getType());
    Assert.assertEquals("main", snapshot0.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot0.getCaptures().getLines().get(10), "arg", "java.lang.String", "2");
    assertCaptureLocals(snapshot0.getCaptures().getLines().get(10), "var1", "int", "1");
    Snapshot snapshot1 = snapshots.get(1);
    Assert.assertEquals(
        "com.datadog.debugger.CapturedSnapshot11", snapshot1.getProbe().getLocation().getType());
    Assert.assertEquals("main", snapshot1.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot1.getCaptures().getEntry(), "arg", "java.lang.String", "2");
    assertCaptureReturnValue(snapshot1.getCaptures().getReturn(), "int", "2");
  }

  @Test
  public void sourceFileProbeScala() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot101";
    final String FILE_NAME = CLASS_NAME + ".scala";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, FILE_NAME, "3"));
    String source = getFixtureContent("/" + FILE_NAME);
    Class<?> testClass = ScalaHelper.compileAndLoad(source, CLASS_NAME, FILE_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assert.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assert.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(3), "value", "int", "31");
  }

  @Test
  public void sourceFileProbeGroovy() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot201";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, CLASS_NAME + ".groovy", "4"));
    String source = getFixtureContent("/" + CLASS_NAME + ".groovy");
    GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
    Class<?> testClass = groovyClassLoader.parseClass(source);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(48, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
    Assert.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
    Assert.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
    assertCaptureArgs(snapshot.getCaptures().getLines().get(4), "value", "int", "31");
  }

  @Test
  public void sourceFileProbeKotlin() {
    final String CLASS_NAME = "CapturedSnapshot301";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createSourceFileProbe(PROBE_ID, CLASS_NAME + ".kt", "4"));
    URL resource = CapturedSnapshotTest.class.getResource("/" + CLASS_NAME + ".kt");
    Assert.assertNotNull(resource);
    List<File> filesToDelete = new ArrayList<>();
    Class<?> testClass = KotlinHelper.compileAndLoad(CLASS_NAME, resource.getFile(), filesToDelete);
    try {
      Object companion = Reflect.on(testClass).get("Companion");
      int result = Reflect.on(companion).call("main", "").get();
      Assert.assertEquals(48, result);
      Snapshot snapshot = assertOneSnapshot(listener);
      Assert.assertNull(snapshot.getCaptures().getEntry());
      Assert.assertNull(snapshot.getCaptures().getReturn());
      Assert.assertEquals(1, snapshot.getCaptures().getLines().size());
      Assert.assertEquals(CLASS_NAME, snapshot.getProbe().getLocation().getType());
      Assert.assertEquals("f1", snapshot.getProbe().getLocation().getMethod());
      assertCaptureArgs(snapshot.getCaptures().getLines().get(4), "value", "int", "31");
    } finally {
      filesToDelete.forEach(File::delete);
    }
  }

  @Test
  public void fieldExtractor() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    SnapshotProbe.Builder builder =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "createSimpleData", "()");
    SnapshotProbe simpleDataProbe =
        builder.capture(1, 100, 255, Limits.DEFAULT_FIELD_COUNT).build();
    builder = createProbeBuilder(PROBE_ID2, CLASS_NAME, "createCompositeData", "()");
    SnapshotProbe compositeDataProbe =
        builder.capture(1, 3, 255, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe, compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
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
    SnapshotProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createCompositeData", "()");
    SnapshotProbe compositeDataProbe =
        builder.capture(2, 3, 255, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Snapshot.CapturedValue returnValue =
        snapshot.getCaptures().getReturn().getLocals().get("@return");
    Map<String, Snapshot.CapturedValue> fields = getFields(returnValue);
    Assert.assertTrue(fields.containsKey("nullsd"));
    Assert.assertTrue(fields.containsKey("l1"));
    Snapshot.CapturedValue s1 = fields.get("s1");
    Map<String, Snapshot.CapturedValue> s1Fields =
        (Map<String, Snapshot.CapturedValue>) s1.getValue();
    Assert.assertEquals("101", String.valueOf(s1Fields.get("intValue").getValue()));
    Assert.assertEquals("foo1", s1Fields.get("strValue").getValue());
    Assert.assertEquals("null", String.valueOf(s1Fields.get("listValue").getValue()));
    Assert.assertEquals(
        DEPTH_REASON, String.valueOf(s1Fields.get("listValue").getNotCapturedReason()));
  }

  @Test
  public void fieldExtractorLength() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    SnapshotProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    SnapshotProbe simpleDataProbe = builder.capture(1, 100, 2, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
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
    SnapshotProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    SnapshotProbe simpleDataProbe = builder.capture(0, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Snapshot.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, Snapshot.CapturedValue> fields = getFields(simpleData);
    Assert.assertEquals(1, fields.size());
    Assert.assertEquals(DEPTH_REASON, fields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
  }

  @Test
  public void fieldExtractorDepth0() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    SnapshotProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    SnapshotProbe simpleDataProbe = builder.capture(0, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Snapshot.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, Snapshot.CapturedValue> simpleDataFields = getFields(simpleData);
    Assert.assertEquals(1, simpleDataFields.size());
    Assert.assertEquals(
        DEPTH_REASON, simpleDataFields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
  }

  @Test
  public void fieldExtractorDepth1() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    SnapshotProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createSimpleData", "()");
    SnapshotProbe simpleDataProbe = builder.capture(1, 100, 50, Limits.DEFAULT_FIELD_COUNT).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, simpleDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Snapshot.CapturedValue simpleData =
        snapshot.getCaptures().getReturn().getLocals().get("simpleData");
    Map<String, Snapshot.CapturedValue> simpleDataFields = getFields(simpleData);
    Assert.assertEquals(3, simpleDataFields.size());
    Assert.assertEquals("foo", simpleDataFields.get("strValue").getValue());
    Assert.assertEquals(42, simpleDataFields.get("intValue").getValue());
    Assert.assertEquals(DEPTH_REASON, simpleDataFields.get("listValue").getNotCapturedReason());
  }

  @Test
  public void fieldExtractorCount2() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    SnapshotProbe.Builder builder =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "createCompositeData", "()");
    SnapshotProbe compositeDataProbe = builder.capture(2, 3, 255, 2).build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, compositeDataProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Snapshot.CapturedValue returnValue =
        snapshot.getCaptures().getReturn().getLocals().get("@return");
    Assert.assertEquals("CapturedSnapshot04$CompositeData", returnValue.getType());
    Map<String, Snapshot.CapturedValue> fields = getFields(returnValue);
    Assert.assertEquals(3, fields.size());
    Assert.assertEquals(
        FIELD_COUNT_REASON, fields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
    Map<String, Snapshot.CapturedValue> s1Fields =
        (Map<String, Snapshot.CapturedValue>) fields.get("s1").getValue();
    Assert.assertEquals("foo1", s1Fields.get("strValue").getValue());
    Assert.assertEquals(101, s1Fields.get("intValue").getValue());
    Map<String, Snapshot.CapturedValue> s2Fields =
        (Map<String, Snapshot.CapturedValue>) fields.get("s2").getValue();
    Assert.assertEquals("foo2", s2Fields.get("strValue").getValue());
    Assert.assertEquals(202, s2Fields.get("intValue").getValue());

    Snapshot.CapturedValue compositeData =
        snapshot.getCaptures().getReturn().getLocals().get("compositeData");
    Map<String, Snapshot.CapturedValue> compositeDataFields = getFields(compositeData);
    Assert.assertEquals(3, compositeDataFields.size());
    Assert.assertEquals(
        FIELD_COUNT_REASON,
        compositeDataFields.get("@" + NOT_CAPTURED_REASON).getNotCapturedReason());
    Assert.assertTrue(compositeDataFields.containsKey("s1"));
    Assert.assertTrue(compositeDataFields.containsKey("s2"));
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
      Assert.fail("should not reach this code");
    } catch (ReflectException ex) {
      Assert.assertEquals("oops", ex.getCause().getCause().getMessage());
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
    Assert.assertEquals(42, result);
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
    SnapshotProbe snapshotProbe =
        new SnapshotProbe(
            LANGUAGE,
            PROBE_ID,
            true,
            null,
            new Where(CLASS_NAME, "main", "int (java.lang.String)", new String[] {"8"}, null),
            ProbeCondition.NONE,
            null,
            new SnapshotProbe.Sampling(1));
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, snapshotProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.on(testClass).call("main", "1").get();
      Assert.assertEquals(3, result);
    }
    Assert.assertTrue(listener.snapshots.size() < 20);
  }

  @Test
  public void globalRateLimitSnapshot() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    SnapshotProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "f1", "(int)").sampling(10).build();
    SnapshotProbe probe2 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "f2", "(int)").sampling(10).build();
    Configuration config =
        new Configuration(
            SERVICE_NAME,
            ORG_ID,
            Arrays.asList(probe1, probe2),
            null,
            null,
            null,
            new SnapshotProbe.Sampling(1),
            null);
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, config);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.on(testClass).call("main", "").get();
      Assert.assertEquals(48, result);
    }
    Assert.assertTrue(
        "actual snapshots: " + listener.snapshots.size(), listener.snapshots.size() < 20);
  }

  @Test
  public void simpleConditionTest() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    SnapshotProbe snapshotProbe =
        createProbeBuilder(PROBE_ID, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(
                new ProbeCondition(
                    DSL.when(
                        DSL.and(
                            // this is always true
                            DSL.and(
                                // this reference is resolved directly from the snapshot
                                DSL.eq(DSL.ref(".fld"), DSL.value(11)),
                                // this reference chain needs to use reflection
                                DSL.eq(DSL.ref(".typed.fld.fld.msg"), DSL.value("hello"))),
                            DSL.or(
                                DSL.eq(DSL.ref(ValueReferences.argument("arg")), DSL.value("5")),
                                DSL.gt(
                                    DSL.ref(ValueReferences.DURATION_REF), DSL.value(500_000L))))),
                    "(.fld == 11 && .typed.fld.fld.msg == 'hello') && (#arg == '5' || @duration > 500000)"))
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, snapshotProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 100; i++) {
      int result = Reflect.on(testClass).call("main", String.valueOf(i)).get();
      Assert.assertTrue((i == 2 && result == 2) || result == 3);
    }
    Assert.assertEquals(1, listener.snapshots.size());
    Snapshot.CapturedValue argument =
        listener.snapshots.get(0).getCaptures().getEntry().getArguments().get("arg");
    Assert.assertEquals("5", getValue(argument));
    Assert.assertEquals("java.lang.String", argument.getType());
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

  private void doMergedProbeConditions(
      ProbeCondition probeCondition1, ProbeCondition probeCondition2, int expectedSnapshots)
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    SnapshotProbe probe1 =
        createProbeBuilder(PROBE_ID1, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(probeCondition1)
            .build();
    SnapshotProbe probe2 =
        createProbeBuilder(PROBE_ID2, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(probeCondition2)
            .build();
    probe1.addAdditionalProbe(probe2);
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, probe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertEquals(expectedSnapshots, listener.snapshots.size());
  }

  @Test
  public void fields() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "f", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assert.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCaptureFieldCount(snapshot.getCaptures().getEntry(), 5);
    assertCaptureFields(snapshot.getCaptures().getEntry(), "intValue", "int", "24");
    assertCaptureFields(snapshot.getCaptures().getEntry(), "doubleValue", "double", "3.14");
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "strValue", "java.lang.String", "foobar");
    assertCaptureFields(
        snapshot.getCaptures().getEntry(),
        "strList",
        "java.util.List",
        Arrays.asList("foo", "bar"));
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "strMap", "java.util.Map", Collections.emptyMap());
    assertCaptureFieldCount(snapshot.getCaptures().getReturn(), 5);
    assertCaptureFields(snapshot.getCaptures().getReturn(), "intValue", "int", "48");
    assertCaptureFields(snapshot.getCaptures().getReturn(), "doubleValue", "double", "3.14");
    assertCaptureFields(snapshot.getCaptures().getReturn(), "strValue", "java.lang.String", "done");
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "strList", "java.util.List", "[foo, bar, done]");
    Map<Object, Object> expectedMap = new HashMap<>();
    expectedMap.put("foo", "bar");
    assertCaptureFields(snapshot.getCaptures().getReturn(), "strMap", "java.util.Map", expectedMap);
  }

  @Test
  public void inheritedFields() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    final String INHERITED_CLASS_NAME = CLASS_NAME + "$Inherited";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(INHERITED_CLASS_NAME, createProbe(PROBE_ID, INHERITED_CLASS_NAME, "f", "()"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "inherited").get();
    Assert.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    // Only Declared fields in the current class are captured, not inherited fields
    assertCaptureFieldCount(snapshot.getCaptures().getEntry(), 2);
    assertCaptureFields(
        snapshot.getCaptures().getEntry(), "strValue", "java.lang.String", "foobar");
    assertCaptureFieldCount(snapshot.getCaptures().getReturn(), 2);
    assertCaptureFields(
        snapshot.getCaptures().getReturn(), "strValue", "java.lang.String", "barfoo");
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
    Assert.assertEquals(8, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Snapshot.CapturedContext context = snapshot.getCaptures().getLines().get(33);
    Assert.assertNotNull(context);
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
    Assert.assertEquals(8, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    Snapshot.CapturedContext context = snapshot.getCaptures().getLines().get(44);
    Assert.assertNotNull(context);
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
    Map<String, Snapshot.CapturedValue> arguments =
        snapshot.getCaptures().getEntry().getArguments();
    // it's important there is no null key in this map, as Jackson is not happy about it
    // it's means here that argument names are not resolved correctly
    Assert.assertFalse(arguments.containsKey(null));
    Assert.assertEquals(3, arguments.size());
    Assert.assertTrue(arguments.containsKey("apiKey"));
    Assert.assertTrue(arguments.containsKey("uriInfo"));
    Assert.assertTrue(arguments.containsKey("value"));
  }

  @Test
  public void noCodeMethods() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot09";
    SnapshotProbe nativeMethodProbe = createProbe(PROBE_ID1, CLASS_NAME, "nativeMethod", "()");
    SnapshotProbe abstractMethodProbe = createProbe(PROBE_ID2, CLASS_NAME, "abstractMethod", "()");
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, nativeMethodProbe, abstractMethodProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(1, result);
    Assert.assertEquals(
        "Cannot instrument an abstract or native method",
        listener.errors.get(PROBE_ID1).get(0).getMessage());
    Assert.assertEquals(
        "Cannot instrument an abstract or native method",
        listener.errors.get(PROBE_ID2).get(0).getMessage());
  }

  @Test
  public void duplicateClassDefinition() throws Exception {
    // this test reproduces a very specific case where we get:
    // java.lang.LinkageError: loader utils.MemClassLoader @1f7ef9ea
    // attempted duplicate class definition for com.datadog.debugger.CapturedSnapshot12.
    // (com.datadog.debugger.CapturedSnapshot12 is in unnamed module of loader utils.MemClassLoader
    // @1f7ef9ea, parent loader 'app')
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot12";
    SnapshotProbe abstractMethodProbe = createProbe(PROBE_ID, CLASS_NAME, "<init>", null);
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, abstractMethodProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    Assert.assertNotNull(testClass);
  }

  @Test
  public void overloadedMethods() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot16";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, createProbe(PROBE_ID, CLASS_NAME, "overload", null));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(63, result);
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
    Assert.assertEquals(48, result);
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
    Assert.assertEquals(2, result);
    Assert.assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    Assert.assertEquals(Snapshot.ProbeDetails.ITW_PROBE_ID, snapshot.getProbe().getId());
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
    Assert.assertEquals(2, result);
    Assert.assertEquals(0, listener.snapshots.size());
  }

  private DebuggerTransformerTest.TestSnapshotListener setupInstrumentTheWorldTransformer(
      String excludeFileName) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerInstrumentTheWorld()).thenReturn(true);
    when(config.getDebuggerExcludeFile()).thenReturn(excludeFileName);
    DebuggerTransformerTest.TestSnapshotListener listener =
        new DebuggerTransformerTest.TestSnapshotListener();
    currentTransformer =
        DebuggerAgent.setupInstrumentTheWorldTransformer(config, instr, listener, null);
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
    Assert.assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    Assert.assertEquals(PROBE_ID, snapshot.getProbe().getId());
    return snapshot;
  }

  private DebuggerTransformerTest.TestSnapshotListener installSingleProbe(
      String typeName, String methodName, String signature, String... lines) {
    SnapshotProbe snapshotProbe = createProbe(PROBE_ID, typeName, methodName, signature, lines);
    return installProbes(typeName, snapshotProbe);
  }

  private DebuggerTransformerTest.TestSnapshotListener installProbes(
      String expectedClassName, Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerVerifyByteCode()).thenReturn(true);
    Collection<SnapshotProbe> snapshotProbes = configuration.getSnapshotProbes();
    Map<String, InstrumentationResult> instrumentationResults = new ConcurrentHashMap<>();
    currentTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            (definition, result) -> instrumentationResults.put(definition.getId(), result));
    instr.addTransformer(currentTransformer);
    DebuggerTransformerTest.TestSnapshotListener listener =
        new DebuggerTransformerTest.TestSnapshotListener();
    DebuggerContext.init(
        listener,
        (id, callingClass) ->
            resolver(id, callingClass, expectedClassName, snapshotProbes, instrumentationResults),
        null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initSnapshotSerializer(new JsonSnapshotSerializer());
    for (SnapshotProbe snapshotProbe : snapshotProbes) {
      if (snapshotProbe.getSampling() != null) {
        ProbeRateLimiter.setRate(
            snapshotProbe.getId(), snapshotProbe.getSampling().getSnapshotsPerSecond());
      }
    }
    if (configuration.getSampling() != null) {
      ProbeRateLimiter.setGlobalRate(configuration.getSampling().getSnapshotsPerSecond());
    }
    return listener;
  }

  private Snapshot.ProbeDetails resolver(
      String id,
      Class<?> callingClass,
      String expectedClassName,
      Collection<SnapshotProbe> snapshotProbes,
      Map<String, InstrumentationResult> instrumentationResults) {
    Assert.assertEquals(expectedClassName, callingClass.getName());
    for (SnapshotProbe probe : snapshotProbes) {
      if (probe.getId().equals(id)) {
        String typeName = probe.getWhere().getTypeName();
        String methodName = probe.getWhere().getMethodName();
        String sourceFile = probe.getWhere().getSourceFile();
        InstrumentationResult result = instrumentationResults.get(probe.getId());
        if (result != null) {
          typeName = result.getTypeName();
          methodName = result.getMethodName();
        }
        List<String> lines =
            Arrays.stream(probe.getWhere().getSourceLines())
                .map(Where.SourceLine::toString)
                .collect(Collectors.toList());

        Snapshot.ProbeLocation location =
            new Snapshot.ProbeLocation(typeName, methodName, sourceFile, lines);

        return new Snapshot.ProbeDetails(
            id,
            location,
            probe.getProbeCondition(),
            probe.concatTags(),
            probe.additionalProbes.stream()
                .map(
                    (ProbeDefinition relatedProbe) ->
                        new Snapshot.ProbeDetails(
                            relatedProbe.id,
                            location,
                            ((SnapshotProbe) relatedProbe).getProbeCondition(),
                            ((SnapshotProbe) relatedProbe).concatTags()))
                .collect(Collectors.toList()));
      }
    }
    return null;
  }

  private DebuggerTransformerTest.TestSnapshotListener installProbes(
      String expectedClassName, SnapshotProbe... snapshotProbes) {
    return installProbes(
        expectedClassName, new Configuration(SERVICE_NAME, ORG_ID, Arrays.asList(snapshotProbes)));
  }

  private void assertCaptureArgs(
      Snapshot.CapturedContext context, String name, String typeName, String value) {
    Snapshot.CapturedValue capturedValue = context.getArguments().get(name);
    Assert.assertEquals(typeName, capturedValue.getType());
    Assert.assertEquals(value, getValue(capturedValue));
  }

  private void assertCaptureLocals(
      Snapshot.CapturedContext context, String name, String typeName, String value) {
    Snapshot.CapturedValue localVar = context.getLocals().get(name);
    Assert.assertEquals(typeName, localVar.getType());
    Assert.assertEquals(value, getValue(localVar));
  }

  private void assertCaptureFields(
      Snapshot.CapturedContext context, String name, String typeName, String value) {
    Snapshot.CapturedValue field = context.getFields().get(name);
    Assert.assertEquals(typeName, field.getType());
    Assert.assertEquals(value, getValue(field));
  }

  private void assertCaptureFields(
      Snapshot.CapturedContext context, String name, String typeName, Collection<?> collection) {
    Snapshot.CapturedValue field = context.getFields().get(name);
    Assert.assertEquals(typeName, field.getType());
    Iterator<?> iterator = collection.iterator();
    for (Object obj : getCollection(field)) {
      if (iterator.hasNext()) {
        Assert.assertEquals(iterator.next(), obj);
      } else {
        Assert.fail("not same number of elements");
      }
    }
  }

  private void assertCaptureFields(
      Snapshot.CapturedContext context,
      String name,
      String typeName,
      Map<Object, Object> expectedMap) {
    Snapshot.CapturedValue field = context.getFields().get(name);
    Assert.assertEquals(typeName, field.getType());
    Map<Object, Object> map = getMap(field);
    Assert.assertEquals(expectedMap.size(), map.size());
    Iterator<Map.Entry<Object, Object>> iterator = expectedMap.entrySet().iterator();
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      Assert.assertTrue(expectedMap.containsKey(entry.getKey()));
      Assert.assertEquals(expectedMap.get(entry.getKey()), entry.getValue());
    }
  }

  private void assertCaptureFieldCount(Snapshot.CapturedContext context, int expectedFieldCount) {
    Assert.assertEquals(expectedFieldCount, context.getFields().size());
  }

  private void assertCaptureReturnValue(
      Snapshot.CapturedContext context, String typeName, String value) {
    Snapshot.CapturedValue returnValue = context.getLocals().get("@return");
    Assert.assertEquals(typeName, returnValue.getType());
    Assert.assertEquals(value, getValue(returnValue));
  }

  private void assertCaptureReturnValue(
      Snapshot.CapturedContext context, String typeName, Map<String, String> expectedFields) {
    Snapshot.CapturedValue returnValue = context.getLocals().get("@return");
    Assert.assertEquals(typeName, returnValue.getType());
    Map<String, Snapshot.CapturedValue> fields = getFields(returnValue);
    for (Map.Entry<String, String> entry : expectedFields.entrySet()) {
      Assert.assertTrue(fields.containsKey(entry.getKey()));
      Snapshot.CapturedValue fieldCapturedValue = fields.get(entry.getKey());
      if (fieldCapturedValue.getNotCapturedReason() != null) {
        Assert.assertEquals(
            entry.getValue(), String.valueOf(fieldCapturedValue.getNotCapturedReason()));
      } else {
        Assert.assertEquals(entry.getValue(), String.valueOf(fieldCapturedValue.getValue()));
      }
    }
  }

  private void assertCaptureThrowable(
      Snapshot.CapturedContext context,
      String typeName,
      String message,
      String methodName,
      int lineNumber) {
    Snapshot.CapturedThrowable throwable = context.getThrowable();
    assertCaptureThrowable(throwable, typeName, message, methodName, lineNumber);
  }

  private void assertCaptureThrowable(
      Snapshot.CapturedThrowable throwable,
      String typeName,
      String message,
      String methodName,
      int lineNumber) {
    Assert.assertNotNull(throwable);
    Assert.assertEquals(typeName, throwable.getType());
    Assert.assertEquals(message, throwable.getMessage());
    Assert.assertNotNull(throwable.getStacktrace());
    Assert.assertFalse(throwable.getStacktrace().isEmpty());
    Assert.assertEquals(methodName, throwable.getStacktrace().get(0).getFunction());
    Assert.assertEquals(lineNumber, throwable.getStacktrace().get(0).getLineNumber());
  }

  private static String getValue(Snapshot.CapturedValue capturedValue) {
    Snapshot.CapturedValue valued = null;
    try {
      valued = VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
      Object obj = valued.getValue();
      return obj != null ? String.valueOf(obj) : null;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static Map<String, Snapshot.CapturedValue> getFields(
      Snapshot.CapturedValue capturedValue) {
    try {
      Snapshot.CapturedValue valued = VALUE_ADAPTER.fromJson(capturedValue.getStrValue());
      Map<String, Snapshot.CapturedValue> results = new HashMap<>();
      if (valued.getNotCapturedReason() != null) {
        results.put(
            "@" + NOT_CAPTURED_REASON,
            Snapshot.CapturedValue.notCapturedReason(null, null, valued.getNotCapturedReason()));
      }
      if (valued.getValue() == null) {
        return results;
      }
      results.putAll((Map<String, Snapshot.CapturedValue>) valued.getValue());
      return results;
    } catch (IOException e) {
      e.printStackTrace();
      return Collections.emptyMap();
    }
  }

  private static Collection<?> getCollection(Snapshot.CapturedValue capturedValue) {
    try {
      Map<String, Object> capturedValueMap = GENERIC_ADAPTER.fromJson(capturedValue.getStrValue());
      List<Object> elements = (List<Object>) capturedValueMap.get("elements");
      if (elements == null) {
        Assert.fail("not a collection");
      }
      List<Object> result = new ArrayList<>();
      for (Object obj : elements) {
        Map<String, Object> element = (Map<String, Object>) obj;
        String type = (String) element.get("type");
        if (type == null) {
          Assert.fail("no type for element");
        }
        if (MoshiSnapshotHelper.isPrimitive(type)) {
          result.add(element.get("value"));
        } else {
          Assert.fail("not implemented");
        }
      }
      return result;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  private Map<Object, Object> getMap(Snapshot.CapturedValue capturedValue) {
    try {
      Map<String, Object> capturedValueMap = GENERIC_ADAPTER.fromJson(capturedValue.getStrValue());
      List<Object> entries = (List<Object>) capturedValueMap.get("entries");
      if (entries == null) {
        Assert.fail("not a map");
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

  private static SnapshotProbe createProbe(
      String id, String typeName, String methodName, String signature, String... lines) {
    return createProbeBuilder(id, typeName, methodName, signature, lines).build();
  }

  private static SnapshotProbe.Builder createProbeBuilder(
      String id, String typeName, String methodName, String signature, String... lines) {
    return SnapshotProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .active(true)
        .where(typeName, methodName, signature, lines)
        .sampling(new SnapshotProbe.Sampling(100));
  }

  private static SnapshotProbe createSourceFileProbe(
      String id, String sourceFile, String... lines) {
    return new SnapshotProbe(
        LANGUAGE,
        id,
        true,
        null,
        new Where(null, null, null, lines, sourceFile),
        ProbeCondition.NONE,
        null,
        null);
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
