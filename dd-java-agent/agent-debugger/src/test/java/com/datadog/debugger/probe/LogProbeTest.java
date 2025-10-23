package com.datadog.debugger.probe;

import static com.datadog.debugger.agent.CapturingTestBase.getConfig;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.probe.LogProbe.Builder;
import com.datadog.debugger.probe.LogProbe.LogStatus;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Config;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
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

  @NotNull
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
    try (AgentScope scope = tracer.activateManualSpan(span)) {
      Builder builder =
          createLog("Budget testing").probeId(ProbeId.newId()).captureSnapshot(captureSnapshot);
      if (sessionId != null) {
        builder.tags("session_id:" + sessionId);
      }
      LogProbe logProbe = builder.build();
      ProbeRateLimiter.setRate(logProbe.id, -1, captureSnapshot);

      CapturedContext entryContext = capturedContext(span, logProbe);
      CapturedContext exitContext = capturedContext(span, logProbe);
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
    try (AgentScope scope = tracer.activateManualSpan(span)) {
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

      CapturedContext entryContext = capturedContext(span, logProbe);
      CapturedContext exitContext = capturedContext(span, logProbe);
      logProbe.evaluate(entryContext, new LogStatus(logProbe), MethodLocation.ENTRY, false);
      logProbe.evaluate(exitContext, new LogStatus(logProbe), MethodLocation.EXIT, false);

      return logProbe.fillSnapshot(
          entryContext, exitContext, emptyList(), new Snapshot(currentThread(), logProbe, 3));
    }
  }

  private static CapturedContext capturedContext(AgentSpan span, ProbeDefinition probeDefinition) {
    CapturedContext context = new CapturedContext();
    context.evaluate(
        probeDefinition,
        "Log Probe test",
        System.currentTimeMillis(),
        MethodLocation.DEFAULT,
        false);
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
