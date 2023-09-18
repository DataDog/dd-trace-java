package com.datadog.debugger.agent;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class LogProbesInstrumentationTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId LOG_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId LOG_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId LOG_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f9", 0);
  private static final String SERVICE_NAME = "service-name";

  private Instrumentation instr = ByteBuddyAgent.install();
  private ClassFileTransformer currentTransformer;

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
    ProbeRateLimiter.resetAll();
  }

  @Test
  public void methodPlainLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line", CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line", snapshot.getMessage());
  }

  @Test
  public void methodTemplateArgLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(
            "this is log line with arg={arg}", CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with arg=1", snapshot.getMessage());
  }

  @Test
  public void methodTemplateArgLogEvaluateAtExit() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe probe =
        createProbeBuilder(
                LOG_ID,
                "this is log line with return={@return}",
                CLASS_NAME,
                "main",
                "int (java.lang.String)")
            .evaluateAt(MethodLocation.EXIT)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with return=3", snapshot.getMessage());
  }

  @Test
  public void mergedMethodTemplateArgLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe logProbe1 =
        createProbe(
            LOG_ID1,
            "this is log line #1 with arg={arg}",
            CLASS_NAME,
            "main",
            "int (java.lang.String)");
    LogProbe logProbe2 =
        createProbe(
            LOG_ID2,
            "this is log line #2 with arg={arg}",
            CLASS_NAME,
            "main",
            "int (java.lang.String)");
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, logProbe1, logProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(2, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertCapturesNull(snapshot0);
    assertEquals("this is log line #1 with arg=1", snapshot0.getMessage());
    Snapshot snapshot1 = listener.snapshots.get(1);
    assertCapturesNull(snapshot1);
    assertEquals("this is log line #2 with arg=1", snapshot1.getMessage());
  }

  @Test
  public void mergedMethodTemplateMainCaptureAdditionalNonCapture()
      throws IOException, URISyntaxException {
    List<Snapshot> snapshots = doMergedMethodTemplateMixCapture(true, false);
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(LOG_ID1.getId(), snapshot0.getProbe().getId());
    assertNotNull(snapshot0.getCaptures().getEntry());
    assertNotNull(snapshot0.getCaptures().getReturn());
    assertEquals("this is log line #1 with arg=1", snapshot0.getMessage());
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(LOG_ID2.getId(), snapshot1.getProbe().getId());
    assertCapturesNull(snapshot1);
    assertEquals("this is log line #2 with arg=1", snapshot1.getMessage());
  }

  @Test
  public void mergedMethodTemplateMainNonCaptureAdditionalCapture()
      throws IOException, URISyntaxException {
    List<Snapshot> snapshots = doMergedMethodTemplateMixCapture(false, true);
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(LOG_ID1.getId(), snapshot0.getProbe().getId());
    assertCapturesNull(snapshot0);
    assertEquals("this is log line #1 with arg=1", snapshot0.getMessage());
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(LOG_ID2.getId(), snapshot1.getProbe().getId());
    assertNotNull(snapshot1.getCaptures().getEntry());
    assertNotNull(snapshot1.getCaptures().getReturn());
    assertEquals("this is log line #2 with arg=1", snapshot1.getMessage());
  }

  private List<Snapshot> doMergedMethodTemplateMixCapture(
      boolean mainCapture, boolean additionalCapture) throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe logProbe1 =
        createProbeBuilder(
                LOG_ID1,
                "this is log line #1 with arg={arg}",
                CLASS_NAME,
                "main",
                "int (java.lang.String)")
            .captureSnapshot(mainCapture)
            .build();
    LogProbe logProbe2 =
        createProbeBuilder(
                LOG_ID2,
                "this is log line #2 with arg={arg}",
                CLASS_NAME,
                "main",
                "int (java.lang.String)")
            .captureSnapshot(additionalCapture)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, logProbe1, logProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(2, listener.snapshots.size());
    return listener.snapshots;
  }

  @Test
  public void linePlainLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line", CLASS_NAME, null, null, "9");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line", snapshot.getMessage());
  }

  @Test
  public void lineTemplateVarLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line with local var={var1}", CLASS_NAME, null, null, "9");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with local var=3", snapshot.getMessage());
  }

  @Test
  public void lineTemplateMultipleVarLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(
            "nullObject={nullObject} sdata={sdata.strValue} cdata={cdata.s1.intValue}",
            CLASS_NAME,
            null,
            null,
            "25");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("nullObject=null sdata=foo cdata=101", snapshot.getMessage());
  }

  @Test
  public void lineTemplateEscapeLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(
            "this is log line with {{curly braces}} and with local var={{{var1}}}",
            CLASS_NAME,
            null,
            null,
            "9");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals(
        "this is log line with {curly braces} and with local var={3}", snapshot.getMessage());
  }

  @Test
  public void lineTemplateInvalidVarLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line with local var={var42}", CLASS_NAME, null, null, "9");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals(
        "this is log line with local var={Cannot find symbol: var42}", snapshot.getMessage());
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals("var42", snapshot.getEvaluationErrors().get(0).getExpr());
    assertEquals("Cannot find symbol: var42", snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void lineTemplateNullFieldLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(
            "this is log line with field={nullObject.intValue}", CLASS_NAME, null, null, "25");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assertions.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals(
        "this is log line with field={Cannot dereference to field: intValue}",
        snapshot.getMessage());
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals("intValue", snapshot.getEvaluationErrors().get(0).getExpr());
    assertEquals(
        "Cannot dereference to field: intValue",
        snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void lineTemplateIndexOutOfBoundsLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(
            "this is log line with element of list={strList[10]}", CLASS_NAME, null, null, "24");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(
        "this is log line with element of list={index[10] out of bounds: [0-3]}",
        snapshot.getMessage());
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals("strList[10]", snapshot.getEvaluationErrors().get(0).getExpr());
    assertEquals(
        "index[10] out of bounds: [0-3]", snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void lineTemplateThisLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line for this={this}", CLASS_NAME, null, null, "24");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assertions.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(
        "this is log line for this={STATIC_STR=strStatic, intValue=48, doubleValue=3.14, strValue=done, strList=...}, ...",
        snapshot.getMessage());
  }

  @Test
  public void conditionWithLogTemplateEvalError() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot08";
    final String LOG_TEMPLATE = "log line with arg={typoArg}";
    LogProbe logProbes =
        createProbeBuilder(LOG_ID, LOG_TEMPLATE, CLASS_NAME, "doit", "int (java.lang.String)")
            .when(
                new ProbeCondition(DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("5"))), "arg == '5'"))
            .evaluateAt(MethodLocation.ENTRY)
            .template(LOG_TEMPLATE, parseTemplate(LOG_TEMPLATE))
            .captureSnapshot(true)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbes);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    Assertions.assertNotNull(snapshot.getCaptures().getEntry());
    Assertions.assertEquals(2, snapshot.getCaptures().getEntry().getArguments().size());
    Assertions.assertEquals(1, snapshot.getEvaluationErrors().size());
    Assertions.assertEquals(
        "Cannot find symbol: typoArg", snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void mergedMethodTemplateMainNoErrorAdditionalLogError()
      throws IOException, URISyntaxException {
    List<Snapshot> snapshots =
        doMergedMethodTemplateMixLogError(
            "this is log line #1 with arg={arg}", "this is log line #2 with arg={typoArg}");
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(LOG_ID1.getId(), snapshot0.getProbe().getId());
    assertNotNull(snapshot0.getCaptures().getEntry());
    assertNotNull(snapshot0.getCaptures().getReturn());
    assertNull(snapshot0.getEvaluationErrors());
    assertEquals("this is log line #1 with arg=1", snapshot0.getMessage());
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(LOG_ID2.getId(), snapshot1.getProbe().getId());
    assertNotNull(snapshot1.getCaptures().getEntry());
    assertNotNull(snapshot1.getCaptures().getReturn());
    assertEquals(
        "this is log line #2 with arg={Cannot find symbol: typoArg}", snapshot1.getMessage());
    assertEquals(1, snapshot1.getEvaluationErrors().size());
    assertEquals(
        "Cannot find symbol: typoArg", snapshot1.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void mergedMethodTemplateMainLogErrorAdditionalNoError()
      throws IOException, URISyntaxException {
    List<Snapshot> snapshots =
        doMergedMethodTemplateMixLogError(
            "this is log line #1 with arg={typoArg}", "this is log line #2 with arg={arg}");
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(LOG_ID1.getId(), snapshot0.getProbe().getId());
    assertNotNull(snapshot0.getCaptures().getEntry());
    assertNotNull(snapshot0.getCaptures().getReturn());
    assertEquals(
        "this is log line #1 with arg={Cannot find symbol: typoArg}", snapshot0.getMessage());
    assertEquals(1, snapshot0.getEvaluationErrors().size());
    assertEquals(
        "Cannot find symbol: typoArg", snapshot0.getEvaluationErrors().get(0).getMessage());
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(LOG_ID2.getId(), snapshot1.getProbe().getId());
    assertNotNull(snapshot1.getCaptures().getEntry());
    assertNotNull(snapshot1.getCaptures().getReturn());
    assertEquals("this is log line #2 with arg=1", snapshot1.getMessage());
    assertNull(snapshot1.getEvaluationErrors());
  }

  @Test
  public void mergedMethodTemplateMainLogErrorAdditionalLogError()
      throws IOException, URISyntaxException {
    List<Snapshot> snapshots =
        doMergedMethodTemplateMixLogError(
            "this is log line #1 with arg={typoArg1}", "this is log line #2 with arg={typoArg2}");
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(LOG_ID1.getId(), snapshot0.getProbe().getId());
    assertNotNull(snapshot0.getCaptures().getEntry());
    assertNotNull(snapshot0.getCaptures().getReturn());
    assertEquals(
        "this is log line #1 with arg={Cannot find symbol: typoArg1}", snapshot0.getMessage());
    assertEquals(1, snapshot0.getEvaluationErrors().size());
    assertEquals(
        "Cannot find symbol: typoArg1", snapshot0.getEvaluationErrors().get(0).getMessage());
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(LOG_ID2.getId(), snapshot1.getProbe().getId());
    assertNotNull(snapshot1.getCaptures().getEntry());
    assertNotNull(snapshot1.getCaptures().getReturn());
    assertEquals(
        "this is log line #2 with arg={Cannot find symbol: typoArg2}", snapshot1.getMessage());
    assertEquals(1, snapshot1.getEvaluationErrors().size());
    assertEquals(
        "Cannot find symbol: typoArg2", snapshot1.getEvaluationErrors().get(0).getMessage());
  }

  private List<Snapshot> doMergedMethodTemplateMixLogError(
      String mainTemplate, String additionalTemplate) throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    LogProbe logProbe1 =
        createProbeBuilder(LOG_ID1, mainTemplate, CLASS_NAME, "main", "int (java.lang.String)")
            .captureSnapshot(true)
            .build();
    LogProbe logProbe2 =
        createProbeBuilder(
                LOG_ID2, additionalTemplate, CLASS_NAME, "main", "int (java.lang.String)")
            .captureSnapshot(true)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener =
        installProbes(CLASS_NAME, logProbe1, logProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assertions.assertEquals(3, result);
    Assertions.assertEquals(2, listener.snapshots.size());
    return listener.snapshots;
  }

  private DebuggerTransformerTest.TestSnapshotListener installSingleProbe(
      String template, String typeName, String methodName, String signature, String... lines) {
    LogProbe logProbe = createProbe(LOG_ID, template, typeName, methodName, signature, lines);
    return installProbes(
        typeName, Configuration.builder().setService(SERVICE_NAME).add(logProbe).build());
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

  private static LogProbe.Builder createProbeBuilder(
      ProbeId id,
      String template,
      String typeName,
      String methodName,
      String signature,
      String... lines) {
    return LogProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .where(typeName, methodName, signature, lines)
        .template(template, parseTemplate(template));
  }

  private static LogProbe createProbe(
      ProbeId id,
      String template,
      String typeName,
      String methodName,
      String signature,
      String... lines) {
    return createProbeBuilder(id, template, typeName, methodName, signature, lines).build();
  }

  private DebuggerTransformerTest.TestSnapshotListener installProbes(
      String expectedClassName, Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    currentTransformer = new DebuggerTransformer(config, configuration);
    instr.addTransformer(currentTransformer);
    DebuggerTransformerTest.TestSnapshotListener listener =
        new DebuggerTransformerTest.TestSnapshotListener();
    DebuggerAgentHelper.injectSink(listener);
    DebuggerContext.init(
        (id, callingClass) ->
            resolver(id, callingClass, expectedClassName, configuration.getLogProbes()),
        null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initValueSerializer(new JsonSnapshotSerializer());
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

  private Snapshot assertOneSnapshot(DebuggerTransformerTest.TestSnapshotListener listener) {
    Assertions.assertFalse(listener.skipped, "Snapshot skipped because " + listener.cause);
    Assertions.assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    Assertions.assertEquals(LOG_ID.getId(), snapshot.getProbe().getId());
    return snapshot;
  }

  private void assertCapturesNull(Snapshot snapshot) {
    Assertions.assertNull(snapshot.getCaptures().getEntry());
    Assertions.assertNull(snapshot.getCaptures().getReturn());
    Assertions.assertNull(snapshot.getCaptures().getLines());
    Assertions.assertNull(snapshot.getCaptures().getCaughtExceptions());
  }
}
