package com.datadog.debugger.agent;

import static com.datadog.debugger.el.DSL.eq;
import static com.datadog.debugger.el.DSL.getMember;
import static com.datadog.debugger.el.DSL.gt;
import static com.datadog.debugger.el.DSL.ref;
import static com.datadog.debugger.el.DSL.value;
import static com.datadog.debugger.probe.SpanDecorationProbe.TargetSpan.ACTIVE;
import static com.datadog.debugger.probe.SpanDecorationProbe.TargetSpan.ROOT;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;
import static utils.InstrumentationTestHelper.getLineForLineProbe;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.TestTraceInterceptor;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Config;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.core.CoreTracer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import org.joor.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

public class SpanDecorationProbeInstrumentationTest extends ProbeInstrumentationTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);
  private static final ProbeId PROBE_ID3 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId PROBE_ID4 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f9", 0);
  private static final ProbeId LINE_PROBE_ID1 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000001", 0);

  private TestTraceInterceptor traceInterceptor = new TestTraceInterceptor();

  @BeforeEach
  public void setUp() {
    CoreTracer tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    tracer.addTraceInterceptor(traceInterceptor);
  }

  @Override
  @AfterEach
  public void after() {
    super.after();
    Redaction.clearUserDefinedTypes();
    ProbeRateLimiter.resetAll();
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
    verify(probeStatusSink).addEmitting(ArgumentMatchers.eq(PROBE_ID));
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
        asList(deco1, deco2, deco3, deco4, deco5),
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
        CLASS_NAME, ROOT, asList(deco1, deco2, deco3, deco4), "process3", "int (java.lang.String)");
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
  public void methodActiveSpanConditionFalse() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(eq(ref("arg"), value("5")), "arg == '5'", "tag1", "{arg}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "0").get();
    assertEquals(84, result);
    assertEquals(1, traceInterceptor.getAllTraces().size());
    MutableSpan span = traceInterceptor.getAllTraces().get(0).get(0);
    // probe executed, but no tag set
    assertFalse(span.getTags().containsKey("tag1"));
    // EMITTING status is not sent
    verify(probeStatusSink, times(0)).addEmitting(ArgumentMatchers.eq(PROBE_ID));
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
    assertEquals(
        "Cannot dereference field: noarg", span.getTags().get("_dd.di.tag1.evaluation_error"));
    assertEquals(1, mockSink.getSnapshots().size());
    Snapshot snapshot = mockSink.getSnapshots().get(0);
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals(
        "Cannot dereference field: noarg", snapshot.getEvaluationErrors().get(0).getMessage());
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
    assertEquals(
        "Cannot dereference field: noarg", snapshot.getEvaluationErrors().get(0).getMessage());
  }

  @Test
  public void methodActiveSpanSynthReturn() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(gt(ref("@return"), value(0)), "@return > '0", "tag1", "{@return}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("84", span.getTags().get("tag1"));
  }

  @Test
  public void methodActiveSpanSynthDuration() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(gt(ref("@duration"), value(0)), "@return > 0", "tag1", "{@duration}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertTrue(Double.parseDouble((String) span.getTags().get("tag1")) > 0);
  }

  @Test
  public void methodActiveSpanSynthException() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration =
        createDecoration(
            eq(getMember(ref("@exception"), "detailMessage"), value("oops")),
            "@exception.detailMessage == 'oops'",
            "tag1",
            "{@exception.detailMessage}");
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decoration, "processWithException", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.on(testClass).call("main", "exception").get();
      Assertions.fail("should not reach this code");
    } catch (RuntimeException ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("oops", span.getTags().get("tag1"));
  }

  @Test
  public void lineActiveSpanSimpleTag() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration = createDecoration("tag1", "{arg}");
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    installSingleSpanDecoration(
        LINE_PROBE_ID1, CLASS_NAME, ACTIVE, decoration, "CapturedSnapshot20.java", line);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("1", span.getTags().get("tag1"));
    assertEquals(LINE_PROBE_ID1.getId(), span.getTags().get("_dd.di.tag1.probe_id"));
    verify(probeStatusSink).addEmitting(ArgumentMatchers.eq(LINE_PROBE_ID1));
  }

  @Test
  public void lineRootSpanTagList() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot21";
    SpanDecorationProbe.Decoration deco1 = createDecoration("tag1", "{arg}");
    SpanDecorationProbe.Decoration deco2 = createDecoration("tag2", "{this.intField}");
    SpanDecorationProbe.Decoration deco3 = createDecoration("tag3", "{strField}");
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    installSingleSpanDecoration(
        LINE_PROBE_ID1,
        CLASS_NAME,
        ROOT,
        asList(deco1, deco2, deco3),
        "CapturedSnapshot21.java",
        line);
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
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    installSingleSpanDecoration(
        LINE_PROBE_ID1, CLASS_NAME, ACTIVE, decoration, "CapturedSnapshot20.java", line);
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
    int line = getLineForLineProbe(CLASS_NAME, LINE_PROBE_ID1);
    installSingleSpanDecoration(
        LINE_PROBE_ID1, CLASS_NAME, ACTIVE, decoration, "CapturedSnapshot20.java", line);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "5").get();
    assertEquals(84, result);
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag1"));
    assertEquals(1, mockSink.getSnapshots().size());
    Snapshot snapshot = mockSink.getSnapshots().get(0);
    assertEquals(1, snapshot.getEvaluationErrors().size());
    assertEquals(
        "Cannot dereference field: noarg", snapshot.getEvaluationErrors().get(0).getMessage());
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
    SpanDecorationProbe.Decoration decoration1 = createDecoration("tag1", "{intLocal}");
    SpanDecorationProbe.Decoration decoration2 = createDecoration("tag2", "{arg}");
    SpanDecorationProbe spanDecoProbe1 =
        createProbeBuilder(
                PROBE_ID1, ACTIVE, singletonList(decoration1), CLASS_NAME, "process", null)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    SpanDecorationProbe spanDecoProbe2 =
        createProbeBuilder(
                PROBE_ID2, ACTIVE, singletonList(decoration2), CLASS_NAME, "process", null)
            .evaluateAt(MethodLocation.ENTRY)
            .build();
    LogProbe logProbe1 =
        LogProbe.builder()
            .probeId(PROBE_ID3)
            .where(CLASS_NAME, "process")
            .captureSnapshot(true)
            .capture(1, 50, 50, 10)
            .build();
    LogProbe logProbe2 =
        LogProbe.builder()
            .probeId(PROBE_ID4)
            .where(CLASS_NAME, "process")
            .captureSnapshot(true)
            .capture(5, 200, 200, 30)
            .build();
    Configuration configuration =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(logProbe1)
            .add(logProbe2)
            .add(spanDecoProbe1)
            .add(spanDecoProbe2)
            .build();
    installSpanDecorationProbes(CLASS_NAME, configuration);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("84", span.getTags().get("tag1"));
    assertEquals(PROBE_ID1.getId(), span.getTags().get("_dd.di.tag1.probe_id"));
    assertEquals("1", span.getTags().get("tag2"));
    assertEquals(PROBE_ID2.getId(), span.getTags().get("_dd.di.tag2.probe_id"));
    List<Snapshot> snapshots = mockSink.getSnapshots();
    assertEquals(2, snapshots.size());
    CapturedContext.CapturedValue intLocal =
        snapshots.get(0).getCaptures().getReturn().getLocals().get("intLocal");
    assertNotNull(intLocal);
  }

  @Test
  public void mixedEntryExit() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot20";
    SpanDecorationProbe.Decoration decoration1 = createDecoration("tag1", "{intLocal}");
    SpanDecorationProbe.Decoration decoration2 = createDecoration("tag2", "{arg}");
    SpanDecorationProbe spanDecoProbe1 =
        createProbeBuilder(
                PROBE_ID1, ACTIVE, singletonList(decoration1), CLASS_NAME, "process", null)
            .evaluateAt(MethodLocation.EXIT)
            .build();
    SpanDecorationProbe spanDecoProbe2 =
        createProbeBuilder(
                PROBE_ID2, ACTIVE, singletonList(decoration2), CLASS_NAME, "process", null)
            .evaluateAt(MethodLocation.ENTRY)
            .build();
    Configuration configuration =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(spanDecoProbe1)
            .add(spanDecoProbe2)
            .build();
    installSpanDecorationProbes(CLASS_NAME, configuration);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(84, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertEquals("84", span.getTags().get("tag1"));
    assertEquals("1", span.getTags().get("tag2"));
  }

  @Test
  public void keywordRedaction() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot28";
    SpanDecorationProbe.Decoration decoration1 = createDecoration("tag1", "{password}");
    SpanDecorationProbe.Decoration decoration2 = createDecoration("tag2", "{this.password}");
    SpanDecorationProbe.Decoration decoration3 = createDecoration("tag3", "{strMap['password']}");
    List<SpanDecorationProbe.Decoration> decorations =
        asList(decoration1, decoration2, decoration3);
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decorations, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "secret123").get();
    assertEquals(42, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertFalse(span.getTags().containsKey("tag1"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag1.probe_id"));
    assertEquals(
        "Could not evaluate the expression because 'password' was redacted",
        span.getTags().get("_dd.di.tag1.evaluation_error"));
    assertFalse(span.getTags().containsKey("tag2"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag2.probe_id"));
    assertEquals(
        "Could not evaluate the expression because 'this.password' was redacted",
        span.getTags().get("_dd.di.tag2.evaluation_error"));
    assertFalse(span.getTags().containsKey("tag3"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag3.probe_id"));
    assertEquals(
        "Could not evaluate the expression because 'strMap[\"password\"]' was redacted",
        span.getTags().get("_dd.di.tag3.evaluation_error"));
  }

  @Test
  public void keywordRedactionConditions() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot28";
    SpanDecorationProbe.Decoration decoration1 =
        createDecoration(
            DSL.contains(DSL.getMember(DSL.ref("this"), "password"), new StringValue("123")),
            "contains(this.password, '123')",
            "tag1",
            "foo");
    SpanDecorationProbe.Decoration decoration2 =
        createDecoration(
            DSL.eq(DSL.ref("password"), DSL.value("123")), "password == '123'", "tag2", "foo");
    SpanDecorationProbe.Decoration decoration3 =
        createDecoration(
            DSL.eq(DSL.index(DSL.ref("strMap"), DSL.value("password")), DSL.value("123")),
            "strMap['password'] == '123'",
            "tag3",
            "foo");
    List<SpanDecorationProbe.Decoration> decorations =
        asList(decoration1, decoration2, decoration3);
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decorations, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "secret123").get();
    assertEquals(42, result);
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag1"));
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag2"));
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag3"));
    assertEquals(1, mockSink.getSnapshots().size());
    Snapshot snapshot = mockSink.getSnapshots().get(0);
    assertEquals(3, snapshot.getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'this.password' was redacted",
        snapshot.getEvaluationErrors().get(0).getMessage());
    assertEquals(
        "Could not evaluate the expression because 'password' was redacted",
        snapshot.getEvaluationErrors().get(1).getMessage());
    assertEquals(
        "Could not evaluate the expression because 'strMap[\"password\"]' was redacted",
        snapshot.getEvaluationErrors().get(2).getMessage());
  }

  @Test
  public void typeRedaction() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot28";
    Config config = mock(Config.class);
    when(config.getDynamicInstrumentationRedactedTypes())
        .thenReturn("com.datadog.debugger.CapturedSnapshot28$Creds");
    Redaction.addUserDefinedTypes(config);
    SpanDecorationProbe.Decoration decoration1 = createDecoration("tag1", "{creds}");
    SpanDecorationProbe.Decoration decoration2 = createDecoration("tag2", "{this.creds}");
    SpanDecorationProbe.Decoration decoration3 = createDecoration("tag3", "{credMap['dave']}");
    List<SpanDecorationProbe.Decoration> decorations =
        asList(decoration1, decoration2, decoration3);
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decorations, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "secret123").get();
    assertEquals(42, result);
    MutableSpan span = traceInterceptor.getFirstSpan();
    assertFalse(span.getTags().containsKey("tag1"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag1.probe_id"));
    assertEquals(
        "Could not evaluate the expression because 'creds' was redacted",
        span.getTags().get("_dd.di.tag1.evaluation_error"));
    assertFalse(span.getTags().containsKey("tag2"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag2.probe_id"));
    assertEquals(
        "Could not evaluate the expression because 'this.creds' was redacted",
        span.getTags().get("_dd.di.tag2.evaluation_error"));
    assertFalse(span.getTags().containsKey("tag3"));
    assertEquals(PROBE_ID.getId(), span.getTags().get("_dd.di.tag3.probe_id"));
    assertEquals(
        "Could not evaluate the expression because 'credMap[\"dave\"]' was redacted",
        span.getTags().get("_dd.di.tag3.evaluation_error"));
  }

  @Test
  public void typeRedactionConditions() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot28";
    Config config = mock(Config.class);
    when(config.getDynamicInstrumentationRedactedTypes())
        .thenReturn("com.datadog.debugger.CapturedSnapshot28$Creds");
    Redaction.addUserDefinedTypes(config);
    SpanDecorationProbe.Decoration decoration1 =
        createDecoration(
            DSL.contains(
                DSL.getMember(DSL.getMember(DSL.ref("this"), "creds"), "secretCode"),
                new StringValue("123")),
            "contains(this.creds.secretCode, '123')",
            "tag1",
            "foo");
    SpanDecorationProbe.Decoration decoration2 =
        createDecoration(
            DSL.eq(DSL.getMember(DSL.ref("creds"), "secretCode"), DSL.value("123")),
            "creds.secretCode == '123'",
            "tag2",
            "foo");
    SpanDecorationProbe.Decoration decoration3 =
        createDecoration(
            DSL.eq(DSL.index(DSL.ref("credMap"), DSL.value("dave")), DSL.value("123")),
            "credMap['dave'] == '123'",
            "tag3",
            "foo");
    List<SpanDecorationProbe.Decoration> decorations =
        asList(decoration1, decoration2, decoration3);
    installSingleSpanDecoration(
        CLASS_NAME, ACTIVE, decorations, "process", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "secret123").get();
    assertEquals(42, result);
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag1"));
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag2"));
    assertFalse(traceInterceptor.getFirstSpan().getTags().containsKey("tag3"));
    assertEquals(1, mockSink.getSnapshots().size());
    Snapshot snapshot = mockSink.getSnapshots().get(0);
    assertEquals(3, snapshot.getEvaluationErrors().size());
    assertEquals(
        "Could not evaluate the expression because 'this.creds' was redacted",
        snapshot.getEvaluationErrors().get(0).getMessage());
    assertEquals(
        "Could not evaluate the expression because 'creds' was redacted",
        snapshot.getEvaluationErrors().get(1).getMessage());
    assertEquals(
        "Could not evaluate the expression because 'credMap[\"dave\"]' was redacted",
        snapshot.getEvaluationErrors().get(2).getMessage());
  }

  @Test
  public void ensureCallingSamplingTagEvalError() throws IOException, URISyntaxException {
    doSamplingTest(this::methodTagEvalError, 1, 1);
  }

  @Test
  public void ensureCallingSamplingMethodInvalidCondition() throws IOException, URISyntaxException {
    doSamplingTest(this::methodActiveSpanInvalidCondition, 1, 1);
  }

  @Test
  public void ensureCallingSamplingLineInvalidCondition() throws IOException, URISyntaxException {
    doSamplingTest(this::lineActiveSpanInvalidCondition, 1, 1);
  }

  @Test
  public void ensureCallingSamplingKeywordRedactionConditions()
      throws IOException, URISyntaxException {
    doSamplingTest(this::keywordRedactionConditions, 1, 1);
  }

  private void doSamplingTest(
      CapturingTestBase.TestMethod testRun, int expectedGlobalCount, int expectedProbeCount)
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

  private SpanDecorationProbe.Decoration createDecoration(String tagName, String valueDsl) {
    List<SpanDecorationProbe.Tag> tags =
        asList(
            new SpanDecorationProbe.Tag(
                tagName, new SpanDecorationProbe.TagValue(valueDsl, parseTemplate(valueDsl))));
    return new SpanDecorationProbe.Decoration(null, tags);
  }

  private SpanDecorationProbe.Decoration createDecoration(
      BooleanExpression expression, String dsl, String tagName, String valueDsl) {
    List<SpanDecorationProbe.Tag> tags =
        asList(
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
    installSingleSpanDecoration(typeName, targetSpan, asList(decoration), methodName, signature);
  }

  private void installSingleSpanDecoration(
      ProbeId probeId,
      String typeName,
      SpanDecorationProbe.TargetSpan targetSpan,
      SpanDecorationProbe.Decoration decoration,
      String sourceFile,
      int line) {
    installSingleSpanDecoration(
        probeId, typeName, targetSpan, asList(decoration), sourceFile, line);
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
      ProbeId probeId,
      String typeName,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorations,
      String sourceFile,
      int line) {
    SpanDecorationProbe probe = createProbe(probeId, targetSpan, decorations, sourceFile, line);
    installSpanDecorationProbes(
        typeName, Configuration.builder().setService(SERVICE_NAME).add(probe).build());
  }

  private static SpanDecorationProbe createProbe(
      ProbeId id,
      SpanDecorationProbe.TargetSpan targetSpan,
      List<SpanDecorationProbe.Decoration> decorationList,
      String typeName,
      String methodName,
      String signature) {
    return createProbeBuilder(id, targetSpan, decorationList, typeName, methodName, signature)
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
      String signature) {
    return SpanDecorationProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .where(typeName, methodName, signature)
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
        expectedClassName, Configuration.builder().setService(SERVICE_NAME).add(probes).build());
  }

  private void installSpanDecorationProbes(String expectedClassName, Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDynamicInstrumentationEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationClassFileDumpEnabled()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    probeStatusSink = mock(ProbeStatusSink.class);
    ProbeMetadata probeMetadata = new ProbeMetadata();
    currentTransformer =
        new DebuggerTransformer(
            config, configuration, null, probeMetadata, new DebuggerSink(config, probeStatusSink));
    instr.addTransformer(currentTransformer);
    mockSink = new MockSink(config, probeStatusSink);
    DebuggerAgentHelper.injectSink(mockSink);
    DebuggerContext.initProbeResolver(probeMetadata::getProbe);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
  }

  static ProbeImplementation resolver(String encodedProbeId, Configuration configuration) {
    List<Collection<? extends ProbeDefinition>> list1 =
        asList(
            configuration.getSpanDecorationProbes(),
            configuration.getLogProbes(),
            configuration.getTriggerProbes());
    for (Collection<? extends ProbeDefinition> list : list1) {

      ProbeImplementation probe = scanForProbe(encodedProbeId, list);
      if (probe != null) {
        return probe;
      }
    }
    return null;
  }

  private static ProbeDefinition scanForProbe(
      String encodedProbeId, Collection<? extends ProbeDefinition> probes) {
    if (probes != null) {
      for (ProbeDefinition probe : probes) {
        if (probe.getProbeId().getEncodedId().equals(encodedProbeId)) {
          return probe;
        }
      }
    }
    return null;
  }
}
