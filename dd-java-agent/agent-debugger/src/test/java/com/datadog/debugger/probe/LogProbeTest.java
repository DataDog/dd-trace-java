package com.datadog.debugger.probe;

import static com.datadog.debugger.agent.CapturingTestBase.getConfig;
import static com.datadog.debugger.el.DSL.and;
import static com.datadog.debugger.el.DSL.bool;
import static com.datadog.debugger.el.DSL.contains;
import static com.datadog.debugger.el.DSL.endsWith;
import static com.datadog.debugger.el.DSL.eq;
import static com.datadog.debugger.el.DSL.filter;
import static com.datadog.debugger.el.DSL.ge;
import static com.datadog.debugger.el.DSL.getMember;
import static com.datadog.debugger.el.DSL.gt;
import static com.datadog.debugger.el.DSL.index;
import static com.datadog.debugger.el.DSL.instanceOf;
import static com.datadog.debugger.el.DSL.isDefined;
import static com.datadog.debugger.el.DSL.isEmpty;
import static com.datadog.debugger.el.DSL.le;
import static com.datadog.debugger.el.DSL.len;
import static com.datadog.debugger.el.DSL.lt;
import static com.datadog.debugger.el.DSL.matches;
import static com.datadog.debugger.el.DSL.not;
import static com.datadog.debugger.el.DSL.or;
import static com.datadog.debugger.el.DSL.ref;
import static com.datadog.debugger.el.DSL.startsWith;
import static com.datadog.debugger.el.DSL.subString;
import static com.datadog.debugger.el.DSL.value;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static datadog.trace.api.debugger.DebuggerMetricCollector.SkippedReason.EVALUATION_TIME_OUT;
import static datadog.trace.api.debugger.DebuggerMetricCollector.SkippedReason.RATE_LIMIT;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.EvaluationTimeOutException;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.datadog.debugger.el.values.StringValue;
import com.datadog.debugger.probe.LogProbe.Builder;
import com.datadog.debugger.probe.LogProbe.LogStatus;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.sampling.ConstantSampler;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class LogProbeTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final String DEBUG_SESSION_ID = "TestSession";
  private static final int BUDGET_RUNS = 1100;

  @Test
  public void testCapture() {
    Builder builder = createLog(null);
    LogProbe snapshotProbe = builder.capture(1, 420, 255, 20).build();
    assertEquals(1, snapshotProbe.getCapture().getMaxReferenceDepth());
    assertEquals(420, snapshotProbe.getCapture().getMaxCollectionSize());
    assertEquals(255, snapshotProbe.getCapture().getMaxLength());
  }

  @Test
  public void testSampling() {
    Builder builder = createLog(null);
    LogProbe snapshotProbe = builder.sampling(0.25).build();
    assertEquals(0.25, snapshotProbe.getSampling().getEventsPerSecond(), 0.01);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("valueExpressions")
  public void equalsSegment(String dsl, Supplier<ValueExpression<?>> exprSupplier) {
    LogProbe.Segment segment1 = new LogProbe.Segment(new ValueScript(exprSupplier.get(), dsl));
    LogProbe.Segment segment2 = new LogProbe.Segment(new ValueScript(exprSupplier.get(), dsl));
    assertEquals(segment1, segment2);
  }

  private static Stream<Arguments> valueExpressions() {
    return Stream.of(
        valueExpression("field", () -> ref("field")),
        valueExpression("obj.field", () -> getMember(ref("obj"), "field")),
        valueExpression("list[0]", () -> index(ref("list"), value(0))),
        valueExpression("map['key']", () -> index(ref("map"), value("key"))),
        valueExpression("len(list)", () -> len(ref("list"))),
        valueExpression("substring(str, 0, 2)", () -> subString(ref("str"), 0, 2)),
        valueExpression(
            "filter(list, {len(@it) > 0})",
            () -> filter(ref("list"), gt(len(ref("@it")), value(0)))),
        valueExpression(
            "any(list, {@it == 1})", () -> bool(DSL.any(ref("list"), eq(ref("@it"), value(1))))),
        valueExpression(
            "all(list, {@it == 1})", () -> bool(DSL.all(ref("list"), eq(ref("@it"), value(1))))),
        valueExpression("isEmpty(list)", () -> bool(isEmpty(ref("list")))),
        valueExpression("isDefined(field)", () -> bool(isDefined(ref("field")))),
        valueExpression(
            "startsWith(str, 'foo')", () -> bool(startsWith(ref("str"), new StringValue("foo")))),
        valueExpression(
            "endsWith(str, 'foo')", () -> bool(endsWith(ref("str"), new StringValue("foo")))),
        valueExpression(
            "matches(str, '[a-z]+')", () -> bool(matches(ref("str"), new StringValue("[a-z]+")))),
        valueExpression("contains(str, 'foo')", () -> bool(contains(ref("str"), value("foo")))),
        valueExpression(
            "instanceof(obj, 'java.lang.String')",
            () -> bool(instanceOf(ref("obj"), value("java.lang.String")))),
        valueExpression("field == 1", () -> bool(eq(ref("field"), value(1)))),
        valueExpression("field > 1", () -> bool(gt(ref("field"), value(1)))),
        valueExpression("field >= 1", () -> bool(ge(ref("field"), value(1)))),
        valueExpression("field < 1", () -> bool(lt(ref("field"), value(1)))),
        valueExpression("field <= 1", () -> bool(le(ref("field"), value(1)))),
        valueExpression("not(isEmpty(list))", () -> bool(not(isEmpty(ref("list"))))),
        valueExpression(
            "isEmpty(list) && isDefined(field)",
            () -> bool(and(isEmpty(ref("list")), isDefined(ref("field"))))),
        valueExpression(
            "isEmpty(list) || isDefined(field)",
            () -> bool(or(isEmpty(ref("list")), isDefined(ref("field"))))));
  }

  private static Arguments valueExpression(String dsl, Supplier<ValueExpression<?>> expr) {
    return Arguments.of(dsl, expr);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("valueExpressions")
  public void hashCodeSegment(String dsl, Supplier<ValueExpression<?>> exprSupplier) {
    LogProbe.Segment segment1 = new LogProbe.Segment(new ValueScript(exprSupplier.get(), dsl));
    LogProbe.Segment segment2 = new LogProbe.Segment(new ValueScript(exprSupplier.get(), dsl));
    assertEquals(segment1.hashCode(), segment2.hashCode());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("differentValueExpressions")
  public void notEqualsSegment(String name, ValueExpression<?> expr1, ValueExpression<?> expr2) {
    // same dsl string on both sides so that only the expression differs
    LogProbe.Segment segment1 = new LogProbe.Segment(new ValueScript(expr1, "dsl"));
    LogProbe.Segment segment2 = new LogProbe.Segment(new ValueScript(expr2, "dsl"));
    assertNotEquals(segment1, segment2);
  }

  private static Stream<Arguments> differentValueExpressions() {
    return Stream.of(
        Arguments.of("ref", ref("field1"), ref("field2")),
        Arguments.of("getMember", getMember(ref("obj"), "field1"), getMember(ref("obj"), "field2")),
        Arguments.of("index key", index(ref("list"), value(0)), index(ref("list"), value(1))),
        Arguments.of("index target", index(ref("list1"), value(0)), index(ref("list2"), value(0))),
        Arguments.of("len", len(ref("list1")), len(ref("list2"))),
        Arguments.of("substring start", subString(ref("str"), 0, 2), subString(ref("str"), 1, 2)),
        Arguments.of("substring end", subString(ref("str"), 0, 2), subString(ref("str"), 0, 3)),
        Arguments.of(
            "substring source", subString(ref("str1"), 0, 2), subString(ref("str2"), 0, 2)),
        Arguments.of(
            "filter predicate",
            filter(ref("list"), gt(len(ref("@it")), value(0))),
            filter(ref("list"), gt(len(ref("@it")), value(1)))),
        Arguments.of(
            "filter source",
            filter(ref("list1"), gt(len(ref("@it")), value(0))),
            filter(ref("list2"), gt(len(ref("@it")), value(0)))),
        Arguments.of(
            "any vs all",
            bool(DSL.any(ref("list"), eq(ref("@it"), value(1)))),
            bool(DSL.all(ref("list"), eq(ref("@it"), value(1))))),
        Arguments.of(
            "any predicate",
            bool(DSL.any(ref("list"), eq(ref("@it"), value(1)))),
            bool(DSL.any(ref("list"), eq(ref("@it"), value(2))))),
        Arguments.of("isEmpty", bool(isEmpty(ref("list1"))), bool(isEmpty(ref("list2")))),
        Arguments.of("isDefined", bool(isDefined(ref("field1"))), bool(isDefined(ref("field2")))),
        Arguments.of(
            "startsWith vs endsWith",
            bool(startsWith(ref("str"), new StringValue("foo"))),
            bool(endsWith(ref("str"), new StringValue("foo")))),
        Arguments.of(
            "startsWith str",
            bool(startsWith(ref("str"), new StringValue("foo"))),
            bool(startsWith(ref("str"), new StringValue("bar")))),
        Arguments.of(
            "matches pattern",
            bool(matches(ref("str"), new StringValue("[a-z]+"))),
            bool(matches(ref("str"), new StringValue("[0-9]+")))),
        Arguments.of(
            "contains value",
            bool(contains(ref("str"), value("foo"))),
            bool(contains(ref("str"), value("bar")))),
        Arguments.of(
            "instanceof",
            bool(instanceOf(ref("obj"), value("java.lang.String"))),
            bool(instanceOf(ref("obj"), value("java.lang.Integer")))),
        Arguments.of(
            "comparison operator",
            bool(gt(ref("field"), value(1))),
            bool(ge(ref("field"), value(1)))),
        Arguments.of(
            "comparison left",
            bool(eq(ref("field1"), value(1))),
            bool(eq(ref("field2"), value(1)))),
        Arguments.of(
            "comparison right", bool(eq(ref("field"), value(1))), bool(eq(ref("field"), value(2)))),
        Arguments.of("not", bool(not(isEmpty(ref("list1")))), bool(not(isEmpty(ref("list2"))))),
        Arguments.of(
            "and vs or",
            bool(and(isEmpty(ref("list")), isDefined(ref("field")))),
            bool(or(isEmpty(ref("list")), isDefined(ref("field"))))),
        Arguments.of(
            "binary left",
            bool(and(isEmpty(ref("list1")), isDefined(ref("field")))),
            bool(and(isEmpty(ref("list2")), isDefined(ref("field"))))),
        Arguments.of(
            "binary right",
            bool(and(isEmpty(ref("list")), isDefined(ref("field1")))),
            bool(and(isEmpty(ref("list")), isDefined(ref("field2"))))));
  }

  @Test
  public void notEqualsCaptureExpression() {
    LogProbe.CaptureExpression base =
        new LogProbe.CaptureExpression(
            "expr", new ValueScript(ref("field"), "field"), new LogProbe.Capture(1, 2, 3, 4));
    assertNotEquals(
        base,
        new LogProbe.CaptureExpression(
            "other", new ValueScript(ref("field"), "field"), new LogProbe.Capture(1, 2, 3, 4)));
    assertNotEquals(
        base,
        new LogProbe.CaptureExpression(
            "expr", new ValueScript(ref("other"), "field"), new LogProbe.Capture(1, 2, 3, 4)));
    assertNotEquals(
        base,
        new LogProbe.CaptureExpression(
            "expr", new ValueScript(ref("field"), "field"), new LogProbe.Capture(5, 2, 3, 4)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("valueExpressions")
  public void equalsCaptureExpression(String dsl, Supplier<ValueExpression<?>> exprSupplier) {
    LogProbe.CaptureExpression captureExpression1 = createCaptureExpression(dsl, exprSupplier);
    LogProbe.CaptureExpression captureExpression2 = createCaptureExpression(dsl, exprSupplier);
    assertEquals(captureExpression1, captureExpression2);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("valueExpressions")
  public void hashCodeCaptureExpression(String dsl, Supplier<ValueExpression<?>> exprSupplier) {
    LogProbe.CaptureExpression captureExpression1 = createCaptureExpression(dsl, exprSupplier);
    LogProbe.CaptureExpression captureExpression2 = createCaptureExpression(dsl, exprSupplier);
    assertEquals(captureExpression1.hashCode(), captureExpression2.hashCode());
  }

  private static LogProbe.CaptureExpression createCaptureExpression(
      String dsl, Supplier<ValueExpression<?>> exprSupplier) {
    return new LogProbe.CaptureExpression(
        "expr", new ValueScript(exprSupplier.get(), dsl), new LogProbe.Capture(1, 2, 3, 4));
  }

  @Test
  public void equalsCoversAllNonTransientFields() throws Exception {
    assertEquals(createFullProbe1(), createFullProbe1());
    LogProbe other = createFullProbe2();
    List<String> uncoveredFields = new ArrayList<>();
    for (Class<?> clazz = LogProbe.class; clazz != Object.class; clazz = clazz.getSuperclass()) {
      for (Field field : clazz.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)
            || Modifier.isTransient(modifiers)
            || field.isSynthetic()) {
          continue;
        }
        field.setAccessible(true);
        LogProbe probe = createFullProbe1();
        Object otherValue = field.get(other);
        assertNotEquals(
            field.get(probe), otherValue, "field " + field.getName() + " must differ in test data");
        field.set(probe, otherValue);
        if (probe.equals(createFullProbe1())) {
          uncoveredFields.add(clazz.getSimpleName() + "." + field.getName());
        }
      }
    }
    assertTrue(uncoveredFields.isEmpty(), "equals does not cover fields: " + uncoveredFields);
  }

  private static LogProbe createFullProbe1() {
    return LogProbe.builder()
        .language(LANGUAGE)
        .probeId("probe-id-1", 1)
        .tags("tag1:value1")
        .where("Class1", "method1")
        .evaluateAt(MethodLocation.ENTRY)
        .template("{arg1}", parseTemplate("{arg1}"))
        .captureSnapshot(true)
        .when(new ProbeCondition(DSL.when(DSL.TRUE), "true"))
        .capture(1, 2, 3, 4)
        .sampling(1.0)
        .captureExpressions(
            singletonList(
                new LogProbe.CaptureExpression(
                    "expr1", new ValueScript(DSL.ref("arg1"), "arg1"), null)))
        .build();
  }

  private static LogProbe createFullProbe2() {
    return LogProbe.builder()
        .language("other")
        .probeId("probe-id-2", 2)
        .tags("tag2:value2")
        .where("Class2", "method2")
        .evaluateAt(MethodLocation.EXIT)
        .template("{arg2}", parseTemplate("{arg2}"))
        .captureSnapshot(false)
        .when(new ProbeCondition(DSL.when(DSL.FALSE), "false"))
        .capture(5, 6, 7, 8)
        .sampling(2.0)
        .captureExpressions(
            singletonList(
                new LogProbe.CaptureExpression(
                    "expr2", new ValueScript(DSL.ref("arg2"), "arg2"), null)))
        .build();
  }

  @Test
  public void coordinatedSamplingEmitsProbeOnceConcurrently() {
    LogProbe.CoordinatedSamplingState state =
        new LogProbe.CoordinatedSamplingState(LogProbe.CoordinatedSamplingState.Status.EMIT);

    long emitted =
        IntStream.range(0, 1_000).parallel().filter(i -> state.tryEmit("probe-id")).count();

    assertEquals(1, emitted);
  }

  @Test
  public void debugSessionActive() {
    assertTrue(
        fillSnapshot(DebugSessionStatus.ACTIVE),
        "Session is active so snapshots should get filled.");
  }

  @Test
  public void debugSessionDisabled() {
    Assertions.assertFalse(
        fillSnapshot(DebugSessionStatus.DISABLED),
        "Session is disabled so snapshots should not get filled.");
  }

  @Test
  public void noDebugSession() {
    assertTrue(
        fillSnapshot(DebugSessionStatus.NONE),
        "With no debug sessions, snapshots should get filled.");
  }

  @Test
  public void budgets() {
    try {
      ProbeRateLimiter.setGlobalSnapshotRate(-1);
      TracerAPI tracer =
          CoreTracer.builder()
              .idGenerationStrategy(IdGenerationStrategy.fromName("random"))
              .build();
      AgentTracer.registerIfAbsent(tracer);
      String sessionId = "12345";

      Result result = getResult(tracer, sessionId, true, null);
      assertEquals(BUDGET_RUNS * LogProbe.CAPTURING_PROBE_BUDGET, result.sink.captures);

      result = getResult(tracer, sessionId, false, null);
      assertEquals(BUDGET_RUNS * LogProbe.NON_CAPTURING_PROBE_BUDGET, result.sink.highRate);

      // run without a session
      result = getResult(tracer, null, true, 100);
      assertEquals(result.count, result.sink.captures);

      result = getResult(tracer, null, false, 100);
      assertEquals(result.count, result.sink.highRate);
    } finally {
      ProbeRateLimiter.resetGlobalRate();
    }
  }

  @Nonnull
  private Result getResult(
      TracerAPI tracer, String sessionId, boolean captureSnapshot, Integer line) {
    BudgetSink sink = new BudgetSink(getConfig(), mock(ProbeStatusSink.class));
    DebuggerAgentHelper.injectSink(sink);
    int count = 0;
    for (int i = 0; i < BUDGET_RUNS; i++) {
      count += runTrace(tracer, captureSnapshot, line, sessionId);
    }
    return new Result(sink, count);
  }

  private static class Result {
    final int count;
    final BudgetSink sink;

    private Result(BudgetSink sink, int count) {
      this.sink = sink;
      this.count = count;
    }
  }

  private int runTrace(TracerAPI tracer, boolean captureSnapshot, Integer line, String sessionId) {
    AgentSpan span = tracer.startSpan("budget testing", "test span");
    if (sessionId != null) {
      span.setTag(Tags.PROPAGATED_DEBUG, sessionId + ":1");
    }
    try (ContextScope scope = tracer.activateManualSpan(span)) {
      Builder builder =
          createLog("Budget testing").probeId(ProbeId.newId()).captureSnapshot(captureSnapshot);
      if (sessionId != null) {
        builder.tags("session_id:" + sessionId);
      }
      LogProbe logProbe = builder.build();
      logProbe.initSamplers();

      CapturedContext entryContext = capturedContext(span, logProbe, MethodLocation.ENTRY);
      CapturedContext exitContext = capturedContext(span, logProbe, MethodLocation.EXIT);
      logProbe.evaluate(entryContext, new LogStatus(logProbe), MethodLocation.ENTRY, false);
      logProbe.evaluate(exitContext, new LogStatus(logProbe), MethodLocation.EXIT, false);

      int budget =
          logProbe.isCaptureSnapshot()
              ? LogProbe.CAPTURING_PROBE_BUDGET
              : LogProbe.NON_CAPTURING_PROBE_BUDGET;
      int runs = budget + 20;

      for (int i = 0; i < runs; i++) {
        if (line == null) {
          logProbe.commit(entryContext, exitContext, emptyList());
        } else {
          logProbe.commit(entryContext, line);
        }
      }
      if (sessionId != null) {
        assertEquals(
            runs, span.getLocalRootSpan().getTag(format("_dd.ld.probe_id.%s", logProbe.id)));
      }
      return runs;
    }
  }

  private boolean fillSnapshot(DebugSessionStatus status) {
    DebuggerAgentHelper.injectSink(new DebuggerSink(getConfig(), mock(ProbeStatusSink.class)));
    TracerAPI tracer =
        CoreTracer.builder().idGenerationStrategy(IdGenerationStrategy.fromName("random")).build();
    AgentTracer.registerIfAbsent(tracer);
    AgentSpan span = tracer.startSpan("log probe debug session testing", "test span");
    try (ContextScope scope = tracer.activateManualSpan(span)) {
      if (status == DebugSessionStatus.ACTIVE) {
        span.setTag(Tags.PROPAGATED_DEBUG, DEBUG_SESSION_ID + ":1");
      } else if (status == DebugSessionStatus.DISABLED) {
        span.setTag(Tags.PROPAGATED_DEBUG, DEBUG_SESSION_ID + ":0");
      }

      Builder builder = createLog("I'm in a debug session").probeId(ProbeId.newId());
      if (status != DebugSessionStatus.NONE) {
        builder.tags(format("session_id:%s", DEBUG_SESSION_ID));
      }

      LogProbe logProbe = builder.build();

      CapturedContext entryContext = capturedContext(span, logProbe, MethodLocation.ENTRY);
      CapturedContext exitContext = capturedContext(span, logProbe, MethodLocation.EXIT);
      logProbe.evaluate(entryContext, new LogStatus(logProbe), MethodLocation.ENTRY, false);
      logProbe.evaluate(exitContext, new LogStatus(logProbe), MethodLocation.EXIT, false);

      return logProbe.fillSnapshot(
          entryContext, exitContext, emptyList(), new Snapshot(currentThread(), logProbe, 3));
    }
  }

  private static CapturedContext capturedContext(
      AgentSpan span, ProbeDefinition probeDefinition, MethodLocation methodLocation) {
    CapturedContext context = new CapturedContext();
    context.evaluate(
        probeDefinition, "Log Probe test", System.currentTimeMillis(), methodLocation, false);
    return context;
  }

  @Test
  public void log() {
    LogProbe logProbe = createLog(null).build();
    assertNull(logProbe.getTemplate());
    assertTrue(logProbe.getSegments().isEmpty());
    logProbe = createLog("plain log line").build();
    assertEquals("plain log line", logProbe.getTemplate());
    assertEquals(1, logProbe.getSegments().size());
    assertEquals("plain log line", logProbe.getSegments().get(0).getStr());
    assertNull(logProbe.getSegments().get(0).getExpr());
    assertNull(logProbe.getSegments().get(0).getParsedExpr());
    logProbe = createLog("simple template log line {arg}").build();
    assertEquals("simple template log line {arg}", logProbe.getTemplate());
    assertEquals(2, logProbe.getSegments().size());
    assertEquals("simple template log line ", logProbe.getSegments().get(0).getStr());
    assertEquals("arg", logProbe.getSegments().get(1).getExpr());
    logProbe = createLog("{arg1}={arg2} {{{count(array)}}}").build();
    assertEquals("{arg1}={arg2} {{{count(array)}}}", logProbe.getTemplate());
    assertEquals(6, logProbe.getSegments().size());
    assertEquals("arg1", logProbe.getSegments().get(0).getExpr());
    assertEquals("=", logProbe.getSegments().get(1).getStr());
    assertEquals("arg2", logProbe.getSegments().get(2).getExpr());
    assertEquals(" {", logProbe.getSegments().get(3).getStr());
    assertEquals("count(array)", logProbe.getSegments().get(4).getExpr());
    assertEquals("}", logProbe.getSegments().get(5).getStr());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ENTRY", "EXIT"})
  public void fillSnapshot_shouldSend(String methodLocation) {
    LogProbe logProbe = createLog(null).evaluateAt(MethodLocation.valueOf(methodLocation)).build();
    CapturedContext entryContext = new CapturedContext();
    CapturedContext exitContext = new CapturedContext();
    LogStatus logEntryStatus = prepareContext(entryContext, logProbe, MethodLocation.ENTRY);
    logEntryStatus.setSampled(true); // force sampled to avoid rate limiting executing tests!
    LogStatus logExitStatus = prepareContext(exitContext, logProbe, MethodLocation.EXIT);
    logExitStatus.setSampled(true); // force sampled to avoid rate limiting executing tests!
    Snapshot snapshot = new Snapshot(currentThread(), logProbe, 10);
    assertTrue(logProbe.fillSnapshot(entryContext, exitContext, null, snapshot));
  }

  @ParameterizedTest
  @MethodSource("statusValues")
  public void fillSnapshot(
      boolean sampled,
      boolean condition,
      boolean conditionErrors,
      boolean logTemplateErrors,
      boolean shouldCommit) {
    LogProbe logProbe = createLog(null).evaluateAt(MethodLocation.EXIT).build();
    CapturedContext entryContext = new CapturedContext();
    CapturedContext exitContext = new CapturedContext();
    LogStatus entryStatus = prepareContext(entryContext, logProbe, MethodLocation.ENTRY);
    fillStatus(entryStatus, sampled, condition, conditionErrors, logTemplateErrors);
    LogStatus exitStatus = prepareContext(exitContext, logProbe, MethodLocation.EXIT);
    fillStatus(exitStatus, sampled, condition, conditionErrors, logTemplateErrors);
    Snapshot snapshot = new Snapshot(currentThread(), logProbe, 10);
    assertEquals(shouldCommit, logProbe.fillSnapshot(entryContext, exitContext, null, snapshot));
  }

  private void fillStatus(
      LogStatus entryStatus,
      boolean sampled,
      boolean condition,
      boolean conditionErrors,
      boolean logTemplateErrors) {
    entryStatus.setSampled(sampled);
    entryStatus.setCondition(condition);
    entryStatus.setConditionErrors(conditionErrors);
    entryStatus.setLogTemplateErrors(logTemplateErrors);
    entryStatus.setLogTemplateErrors(logTemplateErrors);
  }

  private LogStatus prepareContext(
      CapturedContext context, LogProbe logProbe, MethodLocation methodLocation) {
    context.evaluate(logProbe, "", 0, methodLocation, false);
    return (LogStatus) context.getStatus(PROBE_ID.getEncodedId());
  }

  private static Stream<Arguments> statusValues() {
    return Stream.of(
        // sampled, condition, conditionErrors, logTemplateErrors, shouldCommit
        Arguments.of(true, true, false, false, true),
        Arguments.of(true, false, false, false, false),
        Arguments.of(true, false, true, false, true),
        Arguments.of(true, false, true, true, true),
        Arguments.of(true, false, false, true, true),
        Arguments.of(false, false, false, false, false),
        Arguments.of(false, true, false, false, false),
        Arguments.of(false, false, true, false, false),
        Arguments.of(false, false, false, true, false),
        Arguments.of(false, false, true, true, false),
        Arguments.of(false, true, true, true, false));
  }

  @Test
  public void fillSnapshot_shouldSend_exit() {
    LogProbe logProbe = createLog(null).evaluateAt(MethodLocation.EXIT).build();
    CapturedContext entryContext = new CapturedContext();
    prepareContext(entryContext, logProbe, MethodLocation.ENTRY);
    CapturedContext exitContext = new CapturedContext();
    prepareContext(exitContext, logProbe, MethodLocation.EXIT);
    Snapshot snapshot = new Snapshot(currentThread(), logProbe, 10);
    assertTrue(logProbe.fillSnapshot(entryContext, exitContext, null, snapshot));
  }

  @Test
  public void fillSnapshot_shouldSend_evalErrors() {
    LogProbe logProbe = createLog(null).evaluateAt(MethodLocation.EXIT).build();
    CapturedContext entryContext = new CapturedContext();
    LogStatus logStatus = prepareContext(entryContext, logProbe, MethodLocation.ENTRY);
    logStatus.addError(new EvaluationError("expr", "msg1"));
    logStatus.setLogTemplateErrors(true);
    entryContext.addThrowable(new RuntimeException("errorEntry"));
    CapturedContext exitContext = new CapturedContext();
    logStatus = prepareContext(exitContext, logProbe, MethodLocation.EXIT);
    logStatus.addError(new EvaluationError("expr", "msg2"));
    logStatus.setLogTemplateErrors(true);
    exitContext.addThrowable(new RuntimeException("errorExit"));
    Snapshot snapshot = new Snapshot(currentThread(), logProbe, 10);
    assertTrue(logProbe.fillSnapshot(entryContext, exitContext, null, snapshot));
    assertEquals(2, snapshot.getEvaluationErrors().size());
    assertEquals("msg1", snapshot.getEvaluationErrors().get(0).getMessage());
    assertEquals("msg2", snapshot.getEvaluationErrors().get(1).getMessage());
    assertEquals(
        "errorEntry", snapshot.getCaptures().getEntry().getCapturedThrowable().getMessage());
    assertEquals(
        "errorExit", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
  }

  @Test
  public void captureExpressionsInActiveDebugSession() {
    DebuggerAgentHelper.injectSink(new DebuggerSink(getConfig(), mock(ProbeStatusSink.class)));
    TracerAPI tracer =
        CoreTracer.builder().idGenerationStrategy(IdGenerationStrategy.fromName("random")).build();
    AgentTracer.registerIfAbsent(tracer);
    AgentSpan span = tracer.startSpan("log probe capture expression testing", "test span");
    try (ContextScope scope = tracer.activateManualSpan(span)) {
      span.setTag(Tags.PROPAGATED_DEBUG, DEBUG_SESSION_ID + ":1");
      // the probe sampler always rejects: the active session decision must still win
      ProbeRateLimiter.setSamplerSupplier(rate -> new ConstantSampler(false));
      LogProbe logProbe =
          createLog("log line")
              .probeId(ProbeId.newId())
              .evaluateAt(MethodLocation.EXIT)
              .tags(format("session_id:%s", DEBUG_SESSION_ID))
              .when(new ProbeCondition(DSL.when(DSL.eq(DSL.value(1), DSL.value(1))), "1 == 1"))
              .captureExpressions(
                  singletonList(
                      new LogProbe.CaptureExpression(
                          "greeting", new ValueScript(DSL.value("hello"), "'hello'"), null)))
              .build();
      logProbe.initSamplers();
      CapturedContext entryContext = capturedContext(span, logProbe, MethodLocation.ENTRY);
      CapturedContext exitContext = capturedContext(span, logProbe, MethodLocation.EXIT);
      logProbe.evaluate(entryContext, new LogStatus(logProbe), MethodLocation.ENTRY, false);
      logProbe.evaluate(exitContext, new LogStatus(logProbe), MethodLocation.EXIT, false);
      Snapshot snapshot = new Snapshot(currentThread(), logProbe, 3);
      assertTrue(logProbe.fillSnapshot(entryContext, exitContext, emptyList(), snapshot));
      assertEquals(
          "hello",
          snapshot
              .getCaptures()
              .getReturn()
              .getCaptureExpressions()
              .get("greeting")
              .getValue()
              .toString());
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  @Test
  public void coordinatedSamplingActiveSessionOverridesCachedDrop() {
    DebuggerAgentHelper.injectSink(new DebuggerSink(getConfig(), mock(ProbeStatusSink.class)));
    TracerAPI tracer =
        CoreTracer.builder().idGenerationStrategy(IdGenerationStrategy.fromName("random")).build();
    AgentTracer.registerIfAbsent(tracer);
    AgentSpan span = tracer.startSpan("coordinated sampling debug session testing", "test span");
    try (ContextScope scope = tracer.activateManualSpan(span)) {
      // every real sampling decision drops, so the first full-snapshot probe caches a DROP
      // decision for the whole trace before any debug session is active.
      ProbeRateLimiter.setSamplerSupplier(rate -> new ConstantSampler(false));

      LogProbe ordinaryProbeBefore =
          createLog(null)
              .probeId(ProbeId.newId())
              .captureSnapshot(true)
              .evaluateAt(MethodLocation.EXIT)
              .build();
      LogStatus statusBefore = evaluateOnce(ordinaryProbeBefore, MethodLocation.EXIT);
      Assertions.assertFalse(statusBefore.isSampled());

      // the debug session becomes active: a probe belonging to it must emit unconditionally...
      span.setTag(Tags.PROPAGATED_DEBUG, DEBUG_SESSION_ID + ":1");
      LogProbe activeSessionProbe =
          createLog(null)
              .probeId(ProbeId.newId())
              .captureSnapshot(true)
              .evaluateAt(MethodLocation.EXIT)
              .tags(format("session_id:%s", DEBUG_SESSION_ID))
              .build();
      LogStatus activeStatus = evaluateOnce(activeSessionProbe, MethodLocation.EXIT);
      assertTrue(activeStatus.isSampled());
      assertTrue(activeStatus.shouldSend());

      // ...and must not leave the earlier cached DROP in place, or every ordinary full-snapshot
      // probe evaluated afterwards on this trace would keep being suppressed by it.
      LogProbe ordinaryProbeAfter =
          createLog(null)
              .probeId(ProbeId.newId())
              .captureSnapshot(true)
              .evaluateAt(MethodLocation.EXIT)
              .build();
      LogStatus statusAfter = evaluateOnce(ordinaryProbeAfter, MethodLocation.EXIT);
      assertTrue(statusAfter.isSampled());
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  private LogStatus evaluateOnce(LogProbe logProbe, MethodLocation methodLocation) {
    CapturedContext context = new CapturedContext();
    context.evaluate(logProbe, "", 0, methodLocation, false);
    return (LogStatus) context.getStatus(logProbe.getProbeId().getEncodedId());
  }

  @Test
  public void isReadyToCaptureRateLimitedRecordsSkip() {
    DebuggerSink sink = spy(new DebuggerSink(getConfig(), mock(ProbeStatusSink.class)));
    DebuggerAgentHelper.injectSink(sink);
    try {
      ProbeRateLimiter.setSamplerSupplier(rate -> new ConstantSampler(false));
      LogProbe logProbe = createLog(null).build();
      logProbe.initSamplers();
      Assertions.assertFalse(logProbe.isReadyToCapture());
      verify(sink).skipSnapshot(PROBE_ID.getId(), RATE_LIMIT);
    } finally {
      ProbeRateLimiter.setSamplerSupplier(null);
    }
  }

  @Test
  public void evaluateConditionTimeoutRecordsSkipAndConditionErrors() {
    DebuggerSink sink = spy(new DebuggerSink(getConfig(), mock(ProbeStatusSink.class)));
    DebuggerAgentHelper.injectSink(sink);
    ProbeCondition timingOutCondition = mock(ProbeCondition.class);
    when(timingOutCondition.execute(any(), any()))
        .thenThrow(new EvaluationTimeOutException("timeout after 100ms", "slow.expr"));
    LogProbe logProbe =
        createLog(null).evaluateAt(MethodLocation.EXIT).when(timingOutCondition).build();
    CapturedContext context = new CapturedContext();
    LogStatus status = new LogStatus(logProbe);

    // methodLocation (ENTRY) intentionally differs from evaluateAt (EXIT) so that sample() is a
    // no-op here, isolating the skipSnapshot call to evaluateCondition()'s timeout handling.
    logProbe.evaluate(context, status, MethodLocation.ENTRY, false);

    Assertions.assertFalse(status.getCondition());
    assertTrue(status.hasConditionErrors());
    assertEquals(1, status.getErrors().size());
    assertEquals("slow.expr", status.getErrors().get(0).getExpr());
    assertEquals("timeout after 100ms", status.getErrors().get(0).getMessage());
    verify(sink).skipSnapshot(PROBE_ID.getId(), EVALUATION_TIME_OUT);
    verify(sink, times(1)).skipSnapshot(anyString(), any());
  }

  @Test
  public void evaluateConditionFalseDoesNotSkipSnapshot() {
    DebuggerSink sink = spy(new DebuggerSink(getConfig(), mock(ProbeStatusSink.class)));
    DebuggerAgentHelper.injectSink(sink);
    LogProbe logProbe =
        createLog(null)
            .evaluateAt(MethodLocation.EXIT)
            .when(new ProbeCondition(DSL.when(DSL.eq(DSL.value(1), DSL.value(2))), "1 == 2"))
            .build();
    CapturedContext context = new CapturedContext();
    LogStatus status = new LogStatus(logProbe);

    logProbe.evaluate(context, status, MethodLocation.EXIT, false);

    Assertions.assertFalse(status.getCondition());
    Assertions.assertFalse(status.hasConditionErrors());
    verify(sink, never()).skipSnapshot(anyString(), any());
  }

  private Builder createLog(String template) {
    return LogProbe.builder()
        .language(LANGUAGE)
        .probeId(PROBE_ID)
        .where("String.java", 42)
        .template(template, parseTemplate(template));
  }

  private static class BudgetSink extends DebuggerSink {

    public int captures;

    public int highRate;

    public BudgetSink(Config config, ProbeStatusSink probeStatusSink) {
      super(config, probeStatusSink);
    }

    @Override
    public void addHighRateSnapshot(Snapshot snapshot) {
      highRate++;
    }

    @Override
    public void addSnapshot(Snapshot snapshot) {
      captures++;
    }

    @Override
    public void start() {
      super.start();
    }

    @Override
    public void stop() {
      super.stop();
    }
  }
}
