package com.datadog.debugger.agent;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class LogProbesInstrumentationTest {
  private static final String LANGUAGE = "java";
  private static final String LOG_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String LOG_ID1 = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String LOG_ID2 = "beae1807-f3b0-4ea8-a74f-826790c5e6f9";
  private static final String SERVICE_NAME = "service-name";

  private Instrumentation instr = ByteBuddyAgent.install();
  private ClassFileTransformer currentTransformer;

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
  }

  @Test
  public void methodPlainLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line", CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line", snapshot.buildSummary());
  }

  @Test
  public void methodTemplateArgLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe(
            "this is log line with arg={arg}", CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with arg=1", snapshot.buildSummary());
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
            .evaluateAt(ProbeDefinition.MethodLocation.EXIT)
            .build();
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, probe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with return=3", snapshot.buildSummary());
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
    logProbe1.addAdditionalProbe(logProbe2);
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertEquals(2, listener.snapshots.size());
    Snapshot snapshot0 = listener.snapshots.get(0);
    assertCapturesNull(snapshot0);
    assertEquals("this is log line #1 with arg=1", snapshot0.buildSummary());
    Snapshot snapshot1 = listener.snapshots.get(1);
    assertCapturesNull(snapshot1);
    assertEquals("this is log line #2 with arg=1", snapshot1.buildSummary());
  }

  @Test
  public void mergedMethodTemplateMainCaptureAdditionalNonCapture()
      throws IOException, URISyntaxException {
    List<Snapshot> snapshots = doMergedMethodTemplateMixCapture(true, false);
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(LOG_ID1, snapshot0.getProbe().getId());
    assertNotNull(snapshot0.getCaptures().getEntry());
    assertNotNull(snapshot0.getCaptures().getReturn());
    assertEquals("this is log line #1 with arg=1", snapshot0.buildSummary());
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(LOG_ID2, snapshot1.getProbe().getId());
    assertCapturesNull(snapshot1);
    assertEquals("this is log line #2 with arg=1", snapshot1.buildSummary());
  }

  @Test
  public void mergedMethodTemplateMainNonCaptureAdditionalCapture()
      throws IOException, URISyntaxException {
    List<Snapshot> snapshots = doMergedMethodTemplateMixCapture(false, true);
    Snapshot snapshot0 = snapshots.get(0);
    assertEquals(LOG_ID1, snapshot0.getProbe().getId());
    assertCapturesNull(snapshot0);
    assertEquals("this is log line #1 with arg=1", snapshot0.buildSummary());
    Snapshot snapshot1 = snapshots.get(1);
    assertEquals(LOG_ID2, snapshot1.getProbe().getId());
    assertNotNull(snapshot1.getCaptures().getEntry());
    assertNotNull(snapshot1.getCaptures().getReturn());
    assertEquals("this is log line #2 with arg=1", snapshot1.buildSummary());
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
    logProbe1.addAdditionalProbe(logProbe2);
    DebuggerTransformerTest.TestSnapshotListener listener = installProbes(CLASS_NAME, logProbe1);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertEquals(2, listener.snapshots.size());
    return listener.snapshots;
  }

  @Test
  public void linePlainLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line", CLASS_NAME, null, null, "9");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line", snapshot.buildSummary());
  }

  @Test
  public void lineTemplateVarLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line with local var={var1}", CLASS_NAME, null, null, "9");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with local var=3", snapshot.buildSummary());
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
    Assert.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("nullObject=null sdata=foo cdata=101", snapshot.buildSummary());
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
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals(
        "this is log line with {curly braces} and with local var={3}", snapshot.buildSummary());
  }

  @Test
  public void lineTemplateInvalidVarLog() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    DebuggerTransformerTest.TestSnapshotListener listener =
        installSingleProbe("this is log line with local var={var42}", CLASS_NAME, null, null, "9");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with local var=UNDEFINED", snapshot.buildSummary());
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
    Assert.assertEquals(143, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertCapturesNull(snapshot);
    assertEquals("this is log line with field=UNDEFINED", snapshot.buildSummary());
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
    Assert.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals("this is log line with element of list=UNDEFINED", snapshot.buildSummary());
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
    Assert.assertEquals(42, result);
    Snapshot snapshot = assertOneSnapshot(listener);
    assertEquals(
        "this is log line for this={STATIC_STR=strStatic, intValue=48, doubleValue=3.14, strValue=done, strList=..., ...}",
        snapshot.buildSummary());
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
      String id,
      String template,
      String typeName,
      String methodName,
      String signature,
      String... lines) {
    return LogProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .active(true)
        .where(typeName, methodName, signature, lines)
        .template(template, parseTemplate(template));
  }

  private static LogProbe createProbe(
      String id,
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
    when(config.isDebuggerVerifyByteCode()).thenReturn(true);
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
            resolver(
                id,
                callingClass,
                expectedClassName,
                configuration.getLogProbes(),
                instrumentationResults),
        null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    DebuggerContext.initSnapshotSerializer(new JsonSnapshotSerializer());
    return listener;
  }

  private Snapshot.ProbeDetails resolver(
      String id,
      Class<?> callingClass,
      String expectedClassName,
      Collection<LogProbe> logProbes,
      Map<String, InstrumentationResult> instrumentationResults) {
    Assert.assertEquals(expectedClassName, callingClass.getName());
    for (LogProbe probe : logProbes) {
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
            Snapshot.MethodLocation.DEFAULT,
            probe.isCaptureSnapshot(),
            null,
            probe.concatTags(),
            new LogMessageTemplateSummaryBuilder(probe),
            probe.getAdditionalProbes().stream()
                .map(
                    (ProbeDefinition relatedProbe) ->
                        new Snapshot.ProbeDetails(
                            relatedProbe.getId(),
                            location,
                            Snapshot.MethodLocation.DEFAULT,
                            ((LogProbe) relatedProbe).isCaptureSnapshot(),
                            relatedProbe instanceof LogProbe
                                ? ((LogProbe) relatedProbe).getProbeCondition()
                                : null,
                            relatedProbe.concatTags(),
                            new LogMessageTemplateSummaryBuilder((LogProbe) relatedProbe)))
                .collect(Collectors.toList()));
      }
    }
    return null;
  }

  private Snapshot assertOneSnapshot(DebuggerTransformerTest.TestSnapshotListener listener) {
    Assert.assertFalse("Snapshot skipped because " + listener.cause, listener.skipped);
    Assert.assertEquals(1, listener.snapshots.size());
    Snapshot snapshot = listener.snapshots.get(0);
    Assert.assertEquals(LOG_ID, snapshot.getProbe().getId());
    return snapshot;
  }

  private void assertCapturesNull(Snapshot snapshot) {
    Assert.assertNull(snapshot.getCaptures().getEntry());
    Assert.assertNull(snapshot.getCaptures().getReturn());
    Assert.assertNull(snapshot.getCaptures().getLines());
    Assert.assertNull(snapshot.getCaptures().getCaughtExceptions());
  }
}
