package com.datadog.debugger.agent;

import static com.datadog.debugger.el.DSL.eq;
import static com.datadog.debugger.el.DSL.ref;
import static com.datadog.debugger.el.DSL.value;
import static com.datadog.debugger.probe.SpanDecorationProbe.TargetSpan.ACTIVE;
import static com.datadog.debugger.probe.SpanDecorationProbe.TargetSpan.ROOT;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.core.CoreTracer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.joor.Reflect;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SpanDecorationProbeInstrumentationTest extends ProbeInstrumentationTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);

  private TestTraceInterceptor traceInterceptor = new TestTraceInterceptor();

  @BeforeEach
  public void setUp() {
    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    tracer.addTraceInterceptor(traceInterceptor);
  }

  @Test
  public void methodActiveSpanSimpleTag() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration = createDecoration("tag1", "{arg}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("1", span.getTags().get("tag1"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag1.probe_id"));
  }

  @Test
  public void methodActiveSpanTagList() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration deco1 = createDecoration("tag1", "{arg}");
    SpanDecorationProbe.Decoration deco2 = createDecoration("tag2", "{this.intField}");
    SpanDecorationProbe.Decoration deco3 = createDecoration("tag3", "{strField}");
    SpanDecorationProbe.Decoration deco4 = createDecoration("tag4", "{strList[1]}");
    SpanDecorationProbe.Decoration deco5 = createDecoration("Tag+5", "{map['foo3']}");
    installSingleSpanDecoration(
        CLASS_NAME,
        ACTIVE,
        Arrays.asList(deco1, deco2, deco3, deco4, deco5),
        "process",
        "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("1", span.getTags().get("tag1"));
    assertEquals("42", span.getTags().get("tag2"));
    assertEquals("hello", span.getTags().get("tag3"));
    assertEquals("foobar2", span.getTags().get("tag4"));
    assertEquals("bar3", span.getTags().get("tag_5")); // tag name sanitized
  }

  @Test
  public void methodRootSpanTagList() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    SpanDecorationProbe.Decoration deco1 = createDecoration("tag1", "{arg}");
    SpanDecorationProbe.Decoration deco2 = createDecoration("tag2", "{this.intField}");
    SpanDecorationProbe.Decoration deco3 = createDecoration("tag3", "{strField}");
    SpanDecorationProbe.Decoration deco4 = createDecoration("tag4", "{@return}");
    installSingleSpanDecoration(
        CLASS_NAME,
        ROOT,
        Arrays.asList(deco1, deco2, deco3, deco4),
        "process3",
        "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(45, result);
    assertEquals(4, traceInterceptor.getTrace().size());
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("1", span.getTags().get("tag1"));
    assertEquals("42", span.getTags().get("tag2"));
    assertEquals("hello", span.getTags().get("tag3"));
    assertEquals("42", span.getTags().get("tag4"));
  }

  @Test
  public void methodActiveSpanCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(eq(ref("arg"), value("5")), "arg == '5'", "tag1", "{arg}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 10; i++) {
      int result = Reflect.on(testClass).call("main", String.valueOf(i)).get();
      assertEquals(84, result);
    }
    assertEquals(10, traceInterceptor.getAllTraces().size());
    MutableSpan span = traceInterceptor.getAllTraces().get(5).get(0);
    assertEquals("5", span.getTags().get("tag1"));
  }

  @Test
  public void methodTagEvalError() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration = createDecoration("tag1", "{noarg}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertNull(span.getTags().get("tag1"));
    assertEquals("Cannot find symbol: noarg", span.getTags().get("_dd.di.tag1.evaluation_error"));
  }

  @Test
  public void methodActiveSpanInvalidCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(eq(ref("noarg"), value("5")), "noarg == '5'", "tag1", "{arg}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    assertEquals(84, result);
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag1"));
    assertEquals(1, mockSink.getSnapshots().size());
    Snapshot snapshot = mockSink.getSnapshots().get(0);
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals("Cannot find symbol: noarg", snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void lineActiveSpanSimpleTag() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration = createDecoration("tag1", "{arg}");
    installSingleSpanDecoration(CLASS_NAME, ACTIVE, decoration, "CapturedSnapshot20.java", 38);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("1", span.getTags().get("tag1"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag1.probe_id"));
  }

  @Test
  public void lineRootSpanTagList() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    SpanDecorationProbe.Decoration deco1 = createDecoration("tag1", "{arg}");
    SpanDecorationProbe.Decoration deco2 = createDecoration("tag2", "{this.intField}");
    SpanDecorationProbe.Decoration deco3 = createDecoration("tag3", "{strField}");
    installSingleSpanDecoration(
        CLASS_NAME, ROOT, Arrays.asList(deco1, deco2, deco3), "CapturedSnapshot21.java", 67);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(45, result);
    assertEquals(4, traceInterceptor.getTrace().size());
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("1", span.getTags().get("tag1"));
    assertEquals("42", span.getTags().get("tag2"));
    assertEquals("hello", span.getTags().get("tag3"));
  }

  @Test
  public void lineActiveSpanCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(eq(ref("arg"), value("5")), "arg == '5'", "tag1", "{arg}");
    installSingleSpanDecoration(CLASS_NAME, ACTIVE, decoration, "CapturedSnapshot20.java", 38);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    for (int i = 0; i < 10; i++) {
      int result = Reflect.on(testClass).call("main", String.valueOf(i)).get();
      assertEquals(84, result);
    }
    assertEquals(10, traceInterceptor.getAllTraces().size());
    MutableSpan span = traceInterceptor.getAllTraces().get(5).get(0);
    assertEquals("5", span.getTags().get("tag1"));
  }

  @Test
  public void lineActiveSpanInvalidCondition() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(eq(ref("noarg"), value("5")), "arg == '5'", "tag1", "{arg}");
    installSingleSpanDecoration(CLASS_NAME, ACTIVE, decoration, "CapturedSnapshot20.java", 38);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    assertEquals(84, result);
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag1"));
    assertEquals(1, mockSink.getSnapshots().size());
    Snapshot snapshot = mockSink.getSnapshots().get(0);
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals("Cannot find symbol: noarg", snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void nullActiveSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    SpanDecorationProbe.Decoration decoration = createDecoration("tag1", "{arg}");
    installSingleSpanDecoration(CLASS_NAME, ACTIVE, decoration, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, traceInterceptor.getAllTraces().size());
  }

  @Test
  public void mixedWithLogProbes() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration = createDecoration("tag1", "{intLocal}");
    SpanDecorationProbe spanDecoProbe =
        createProbeBuilder(
                PROBE_ID, ACTIVE, singletonList(decoration), CLASS_NAME, "process", null, null)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    LogProbe logProbe1 =
        LogProbe.builder()
            .probeId(PROBE_ID1)
            .where(CLASS_NAME, "process")
            .captureSnapshot(true)
            .build();
    LogProbe logProbe2 =
        LogProbe.builder()
            .probeId(PROBE_ID2)
            .where(CLASS_NAME, "process")
            .captureSnapshot(true)
            .build();
    Configuration configuration =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(logProbe1)
            .add(logProbe2)
            .add(spanDecoProbe)
            .build();
    installSpanDecorationProbes(CLASS_NAME, configuration);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("84", span.getTags().get("tag1"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag1.probe_id"));
    List<Snapshot> snapshots = mockSink.getSnapshots();
    assertEquals(2, snapshots.size());
    CapturedContext.CapturedValue intLocal =
        snapshots.get(0).getCaptures().getReturn().getLocals().get("intLocal");
    assertNotNull(intLocal);
  }

  private SpanDecorationProbe.Decoration createDecoration(String tagName, String valueDsl) {
    List<SpanDecorationProbe.Tag> tags =
        Arrays.asList(
            new SpanDecorationProbe.Tag(
                tagName, new SpanDecorationProbe.TagValue(valueDsl, parseTemplate(valueDsl))));
    return new SpanDecorationProbe.Decoration(null, tags);
  }

  private SpanDecorationProbe.Decoration createDecoration(
      BooleanExpression expression, String dsl, String tagName, String valueDsl) {
    List<SpanDecorationProbe.Tag> tags =
        Arrays.asList(
            new SpanDecorationProbe.Tag(
                tagName, new SpanDecorationProbe.TagValue(valueDsl, parseTemplate(valueDsl))));
    return new SpanDecorationProbe.Decoration(new ProbeCondition(DSL.when(expression), dsl), tags);
  }

  private void installSingleSpanDecoration(
      String typeName,
      SpanDecorationProbe.TargetSpan targetSpan,
      SpanDecorationProbe.Decoration decoration,
      String methodName,
      String signature) {
    installSingleSpanDecoration(
        typeName, targetSpan, Arrays.asList(decoration), methodName, signature);
  }

  private void installSingleSpanDecoration(
      String typeName,
      SpanDecorationProbe.TargetSpan targetSpan,
      SpanDecorationProbe.Decoration decoration,
      String sourceFile,
      int line) {
    installSingleSpanDecoration(typeName, targetSpan, Arrays.asList(decoration), sourceFile, line);
  }

  private void installSingleSpanDecoration(
      String typeName,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorations,
      String methodName,
      String signature) {
    SpanDecorationProbe probe =
        createProbe(PROBE_ID, targetSpan, decorations, typeName, methodName, signature);
    installSpanDecorationProbes(
        typeName, Configuration.builder().setService(SERVICE_NAME).add(probe).build());
  }

  private void installSingleSpanDecoration(
      String typeName,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorations,
      String sourceFile,
      int line) {
    SpanDecorationProbe probe = createProbe(PROBE_ID, targetSpan, decorations, sourceFile, line);
    installSpanDecorationProbes(
        typeName, Configuration.builder().setService(SERVICE_NAME).add(probe).build());
  }

  private static SpanDecorationProbe createProbe(
      ProbeId id,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorationList,
      String typeName,
      String methodName,
      String signature,
      String... lines) {
    return createProbeBuilder(
            id, targetSpan, decorationList, typeName, methodName, signature, lines)
        .evaluateAt(MethodLocation.EXIT)
        .build();
  }

  private static SpanDecorationProbe createProbe(
      ProbeId id,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorationList,
      String sourceFile,
      int line) {
    return createProbeBuilder(id, targetSpan, decorationList, sourceFile, line).build();
  }

  private static SpanDecorationProbe.Builder createProbeBuilder(
      ProbeId id,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorationList,
      String typeName,
      String methodName,
      String signature,
      String... lines) {
    return SpanDecorationProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .where(typeName, methodName, signature, lines)
        .evaluateAt(MethodLocation.EXIT)
        .targetSpan(targetSpan)
        .decorate(decorationList);
  }

  private static SpanDecorationProbe.Builder createProbeBuilder(
      ProbeId id,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorationList,
      String sourceFile,
      int line) {
    return SpanDecorationProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .where(sourceFile, line)
        .evaluateAt(MethodLocation.EXIT)
        .targetSpan(targetSpan)
        .decorate(decorationList);
  }

  private void installSpanProbes(String expectedClassName, SpanDecorationProbe... probes) {
    installSpanDecorationProbes(
        expectedClassName,
        Configuration.builder()
            .setService(SERVICE_NAME)
            .addSpanDecorationProbes(Arrays.asList(probes))
            .build());
  }

  private void installSpanDecorationProbes(String expectedClassName, Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    probeStatusSink = mock(ProbeStatusSink.class);
    currentTransformer =
        new DebuggerTransformer(
            config, configuration, null, new DebuggerSink(config, probeStatusSink));
    instr.addTransformer(currentTransformer);
    mockSink = new MockSink();
    DebuggerAgentHelper.injectSink(mockSink);
    DebuggerContext.init(
        (id, callingClass) -> resolver(id, callingClass, expectedClassName, configuration), null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
  }

  private ProbeImplementation resolver(
      String id, Class<?> callingClass, String expectedClassName, Configuration configuration) {
    Assertions.assertEquals(expectedClassName, callingClass.getName());
    for (SpanDecorationProbe probe : configuration.getSpanDecorationProbes()) {
      if (probe.getId().equals(id)) {
        return probe;
      }
    }
    for (LogProbe probe : configuration.getLogProbes()) {
      if (probe.getId().equals(id)) {
        return probe;
      }
    }
    return null;
  }

  private static class TestTraceInterceptor implements TraceInterceptor {
    private Collection<? extends MutableSpan> currentTrace;
    private List<List<? extends MutableSpan>> allTraces = new ArrayList<>();

    @Override
    public Collection<? extends MutableSpan> onTraceComplete(
        Collection<? extends MutableSpan> trace) {
      currentTrace = trace;
      allTraces.add(new ArrayList<>(trace));
      return trace;
    }

    @Override
    public int priority() {
      return 0;
    }

    public Collection<? extends MutableSpan> getTrace() {
      return currentTrace;
    }

    public MutableSpan getFirstSpan() {
      if (currentTrace == null) {
        return null;
      }
      return currentTrace.iterator().next();
    }

    public List<List<? extends MutableSpan>> getAllTraces() {
      return allTraces;
    }
  }
}
