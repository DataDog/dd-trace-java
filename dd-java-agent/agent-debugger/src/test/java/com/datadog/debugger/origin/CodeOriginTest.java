package com.datadog.debugger.origin;

import static com.datadog.debugger.util.TestHelper.setFieldInConfig;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_PREFIX;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_TYPE;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.agent.CapturingTestBase;
import com.datadog.debugger.codeorigin.DefaultCodeOriginRecorder;
import com.datadog.debugger.probe.CodeOriginProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.joor.Reflect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CodeOriginTest extends CapturingTestBase {

  private static final ProbeId CODE_ORIGIN_ID1 = new ProbeId("code origin 1", 0);

  private static final ProbeId CODE_ORIGIN_ID2 = new ProbeId("code origin 2", 0);

  private static final ProbeId CODE_ORIGIN_DOUBLE_ENTRY_ID =
      new ProbeId("double entry code origin", 0);

  private static final int MAX_FRAMES = 20;

  private DefaultCodeOriginRecorder codeOriginRecorder;

  private TestSnapshotListener listener;

  private TestTraceInterceptor traceInterceptor;

  public static ClassNameFilter classNameFilter =
      new ClassNameFiltering(
          new HashSet<>(
              asList(
                  "sun",
                  "org.junit",
                  "java.",
                  "org.gradle",
                  "com.sun",
                  "worker.org.gradle",
                  "datadog",
                  "com.datadog.debugger.probe",
                  "com.datadog.debugger.codeorigin")));

  @Override
  @BeforeEach
  public void before() {
    super.before();
    traceInterceptor = new TestTraceInterceptor();

    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    tracer.addTraceInterceptor(traceInterceptor);

    setFieldInConfig(Config.get(), "debuggerCodeOriginEnabled", true);
    setFieldInConfig(Config.get(), "distributedDebuggerEnabled", true);
    setFieldInConfig(InstrumenterConfig.get(), "codeOriginEnabled", true);
  }

  @Test
  public void basicInstrumentation() throws Exception {
    final String className = "com.datadog.debugger.CodeOrigin01";

    installProbes(codeOriginProbes(className));
    final Class<?> testClass = compileAndLoadClass(className);
    checkResults(testClass, "fullTrace", 0);
  }

  @Test
  public void withDebug1() throws Exception {
    final String className = "com.datadog.debugger.CodeOrigin02";
    installProbes();
    final Class<?> testClass = compileAndLoadClass(className);
    codeOriginRecorder.captureCodeOrigin(testClass.getMethod("entry"), true, true);
    codeOriginRecorder.captureCodeOrigin(testClass.getMethod("exit"), false, true);
    checkResults(testClass, "fullTrace", 0);
    checkResults(testClass, "debug_1", 2);
  }

  @Test
  public void withLogProbe() throws Exception {
    final String CLASS_NAME = "com.datadog.debugger.CodeOrigin03";
    installProbes(
        createProbeBuilder(PROBE_ID, CLASS_NAME, "entry", "()").captureSnapshot(true).build());
    final Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    codeOriginRecorder.captureCodeOrigin(testClass.getMethod("entry"), true, true);
    codeOriginRecorder.captureCodeOrigin(testClass.getMethod("exit"), false, true);
    checkResults(testClass, "debug_1", 3);
  }

  @Test
  public void doubleEntry() throws IOException, URISyntaxException {
    final String className = "com.datadog.debugger.CodeOrigin05";

    installProbes(
        new CodeOriginProbe(CODE_ORIGIN_ID1, true, Where.of(className, "entry", "()", "53"), true),
        new CodeOriginProbe(CODE_ORIGIN_ID2, false, Where.of(className, "exit", "()", "62"), true),
        new CodeOriginProbe(
            CODE_ORIGIN_DOUBLE_ENTRY_ID,
            true,
            Where.of(className, "doubleEntry", "()", "66"),
            true));
    final Class<?> testClass = compileAndLoadClass(className);
    checkResults(testClass, "fullTrace", 0);
    List<? extends MutableSpan> trace = traceInterceptor.getTrace();
    MutableSpan span = trace.get(0);
    // this should be entry but until we get the ordering resolved, it's this.
    assertEquals("entry", span.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "method")));
  }

  @Test
  public void stackDepth() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CodeOrigin04";
    installProbes(
        new CodeOriginProbe(CODE_ORIGIN_ID1, true, Where.of(CLASS_NAME, "exit", "()", "39"), true));

    Class<?> testClass = compileAndLoadClass("com.datadog.debugger.CodeOrigin04");
    countFrames(testClass, 10);
    countFrames(testClass, 100);
  }

  private void countFrames(Class<?> testClass, int loops) {
    int result = Reflect.onClass(testClass).call("main", loops).get();
    assertEquals(loops, result);
    long count =
        traceInterceptor.getTrace().stream()
            .filter(s -> s.getOperationName().equals("exit"))
            .flatMap(s -> s.getTags().keySet().stream())
            .filter(key -> key.contains("frames") && key.endsWith("method"))
            .count();
    assertTrue(count <= MAX_FRAMES);
  }

  @Test
  public void testCaptureCodeOriginEntry() {
    installProbes();
    CodeOriginProbe probe = codeOriginRecorder.getProbe(codeOriginRecorder.captureCodeOrigin(true));
    assertNotNull(probe);
    assertTrue(probe.entrySpanProbe());
  }

  @Test
  public void testCaptureCodeOriginExit() {
    installProbes();
    CodeOriginProbe probe =
        codeOriginRecorder.getProbe(codeOriginRecorder.captureCodeOrigin(false));
    assertNotNull(probe);
    assertFalse(probe.entrySpanProbe());
  }

  @Test
  public void testCaptureCodeOriginWithExplicitInfo()
      throws IOException, URISyntaxException, NoSuchMethodException {
    final String CLASS_NAME = "com.datadog.debugger.CodeOrigin04";
    final Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    installProbes();
    CodeOriginProbe probe =
        codeOriginRecorder.getProbe(
            codeOriginRecorder.captureCodeOrigin(
                testClass.getMethod("main", int.class), true, true));
    assertNotNull(probe, "The probe should have been created.");
    assertTrue(probe.entrySpanProbe(), "Should be an entry probe.");
  }

  @Test
  public void testDuplicateInstrumentations()
      throws IOException, URISyntaxException, NoSuchMethodException {
    final String CLASS_NAME = "com.datadog.debugger.CodeOrigin04";
    final Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    installProbes();
    String probe1 =
        codeOriginRecorder.captureCodeOrigin(testClass.getMethod("main", int.class), true, true);
    String probe2 =
        codeOriginRecorder.captureCodeOrigin(testClass.getMethod("main", int.class), true, true);
    assertEquals(probe1, probe2);
  }

  @NotNull
  private CodeOriginProbe[] codeOriginProbes(String type) {
    CodeOriginProbe entry =
        new CodeOriginProbe(CODE_ORIGIN_ID1, true, Where.of(type, "entry", "()", "53"), true);
    CodeOriginProbe exit =
        new CodeOriginProbe(CODE_ORIGIN_ID2, false, Where.of(type, "exit", "()", "60"), true);
    return new CodeOriginProbe[] {entry, exit};
  }

  private void checkResults(Class<?> testClass, String parameter, int snapshotsExpected) {
    int result = Reflect.onClass(testClass).call("main", parameter).get();
    assertEquals(0, result);
    List<DDSpan> spans = (List<DDSpan>) traceInterceptor.getTrace();
    assertEquals(3, spans.size());
    assertEquals("main", spans.get(2).getLocalRootSpan().getOperationName());

    List<DDSpan> list =
        spans.stream()
            .filter(span -> !span.getOperationName().equals("exit"))
            .collect(Collectors.toList());

    for (DDSpan span : list) {
      checkEntrySpanTags(span, snapshotsExpected != 0);
    }

    assertEquals(
        snapshotsExpected,
        listener.snapshots.stream()
            .filter(s -> s.getCaptures().getEntry() != null && s.getCaptures().getReturn() != null)
            .collect(Collectors.toList())
            .size());

    Optional<DDSpan> exit =
        spans.stream().filter(span -> span.getOperationName().equals("exit")).findFirst();
    assertTrue(exit.isPresent());
    exit.ifPresent(span -> checkExitSpanTags(span, false));
  }

  @Override
  protected TestSnapshotListener installProbes(ProbeDefinition... probes) {
    listener = super.installProbes(probes);

    DebuggerContext.initClassNameFilter(classNameFilter);
    codeOriginRecorder =
        new DefaultCodeOriginRecorder(
            config,
            configurationUpdater,
            new AgentTaskScheduler(TASK_SCHEDULER) {
              @Override
              public void execute(Runnable target) {
                target.run();
              }
            });
    DebuggerContext.initCodeOrigin(codeOriginRecorder);

    return listener;
  }

  private static void checkEntrySpanTags(DDSpan span, boolean includeSnapshot) {
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "file"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "line"));
    assertNotEquals(-1, span.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "line")));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "method"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "signature"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "type"));

    if (includeSnapshot) {
      assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    } else {
      assertKeyNotPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    }
  }

  private static void assertKeyPresent(DDSpan span, String key) {
    assertNotNull(
        span.getTag(key),
        format(
            "'%s' key missing in '%s' span. current keys:  %s",
            key, span.getOperationName(), ldKeys(span)));
  }

  private static void assertKeyNotPresent(MutableSpan span, String key) {
    assertNull(
        span.getTag(key),
        format("'%s' key found in '%s' span when it shouldn't be.", key, span.getOperationName()));
  }

  private static void checkExitSpanTags(DDSpan span, boolean includeSnapshot) {
    String keys =
        format("Existing keys for %s: %s", span.getOperationName(), new TreeSet<>(ldKeys(span)));

    assertKeyPresent(span, DD_CODE_ORIGIN_TYPE);
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "file"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "line"));
    assertNotEquals(-1, span.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "line")));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "method"));
    assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "type"));
    if (includeSnapshot) {
      assertKeyPresent(span, format(DD_CODE_ORIGIN_FRAME, 0, "snapshot_id"));
    }

    MutableSpan rootSpan = span.getLocalRootSpan();
    assertEquals(rootSpan.getTag(DD_CODE_ORIGIN_TYPE), "entry", keys);
    Object file = rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "file"));
    assertNotNull(file, rootSpan.getTags().toString());
    assertNotNull(rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "line")));
    assertNotEquals(-1, rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "line")));
    assertNotNull(rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "method")));
    assertNotNull(rootSpan.getTag(format(DD_CODE_ORIGIN_FRAME, 0, "type")));
  }

  private static Set<String> ldKeys(MutableSpan span) {
    return span.getTags().keySet().stream()
        .filter(key -> key.startsWith(DD_CODE_ORIGIN_PREFIX))
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
