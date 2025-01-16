package com.datadog.debugger.probe;

import static com.datadog.debugger.agent.CapturingTestBase.mockConfig;
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
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.util.UUID;
import java.util.stream.Stream;
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

  @Test
  public void testCapture() {
    LogProbe.Builder builder = createLog(null);
    LogProbe snapshotProbe = builder.capture(1, 420, 255, 20).build();
    Assertions.assertEquals(1, snapshotProbe.getCapture().getMaxReferenceDepth());
    Assertions.assertEquals(420, snapshotProbe.getCapture().getMaxCollectionSize());
    Assertions.assertEquals(255, snapshotProbe.getCapture().getMaxLength());
  }

  @Test
  public void testSampling() {
    LogProbe.Builder builder = createLog(null);
    LogProbe snapshotProbe = builder.sampling(0.25).build();
    Assertions.assertEquals(0.25, snapshotProbe.getSampling().getEventsPerSecond(), 0.01);
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

  private boolean fillSnapshot(DebugSessionStatus status) {
    DebuggerAgentHelper.injectSink(new DebuggerSink(mockConfig(), mock(ProbeStatusSink.class)));
    TracerAPI tracer =
        CoreTracer.builder().idGenerationStrategy(IdGenerationStrategy.fromName("random")).build();
    AgentTracer.registerIfAbsent(tracer);
    AgentSpan span = tracer.startSpan("log probe debug session testing", "test span");
    try (AgentScope scope = tracer.activateSpan(span, ScopeSource.MANUAL)) {
      if (status == DebugSessionStatus.ACTIVE) {
        span.setTag(Tags.PROPAGATED_DEBUG, DEBUG_SESSION_ID + ":1");
      } else if (status == DebugSessionStatus.DISABLED) {
        span.setTag(Tags.PROPAGATED_DEBUG, DEBUG_SESSION_ID + ":0");
      }

      Builder builder =
          createLog("I'm in a debug session").probeId(UUID.randomUUID().toString(), 0);
      if (status != DebugSessionStatus.NONE) {
        builder.tags(format("session_id:%s", DEBUG_SESSION_ID));
      }

      LogProbe logProbe = builder.build();

      CapturedContext entryContext = capturedContext(span, logProbe);
      CapturedContext exitContext = capturedContext(span, logProbe);
      logProbe.evaluate(entryContext, new LogStatus(logProbe), MethodLocation.ENTRY);
      logProbe.evaluate(exitContext, new LogStatus(logProbe), MethodLocation.EXIT);

      return logProbe.fillSnapshot(
          entryContext, exitContext, emptyList(), new Snapshot(currentThread(), logProbe, 3));
    }
  }

  private static CapturedContext capturedContext(AgentSpan span, ProbeDefinition probeDefinition) {
    CapturedContext context = new CapturedContext();
    context.evaluate(
        probeDefinition.getProbeId().getEncodedId(),
        probeDefinition,
        "Log Probe test",
        System.currentTimeMillis(),
        MethodLocation.DEFAULT);
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
    LogProbe.LogStatus logEntryStatus =
        prepareContext(entryContext, logProbe, MethodLocation.ENTRY);
    logEntryStatus.setSampled(true); // force sampled to avoid rate limiting executing tests!
    LogProbe.LogStatus logExitStatus = prepareContext(exitContext, logProbe, MethodLocation.EXIT);
    logExitStatus.setSampled(true); // force sampled to avoid rate limiting executing tests!
    Snapshot snapshot = new Snapshot(Thread.currentThread(), logProbe, 10);
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
    LogProbe.LogStatus entryStatus = prepareContext(entryContext, logProbe, MethodLocation.ENTRY);
    fillStatus(entryStatus, sampled, condition, conditionErrors, logTemplateErrors);
    LogProbe.LogStatus exitStatus = prepareContext(exitContext, logProbe, MethodLocation.EXIT);
    fillStatus(exitStatus, sampled, condition, conditionErrors, logTemplateErrors);
    Snapshot snapshot = new Snapshot(Thread.currentThread(), logProbe, 10);
    assertEquals(shouldCommit, logProbe.fillSnapshot(entryContext, exitContext, null, snapshot));
  }

  private void fillStatus(
      LogProbe.LogStatus entryStatus,
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

  private LogProbe.LogStatus prepareContext(
      CapturedContext context, LogProbe logProbe, MethodLocation methodLocation) {
    context.evaluate(PROBE_ID.getEncodedId(), logProbe, "", 0, methodLocation);
    return (LogProbe.LogStatus) context.getStatus(PROBE_ID.getEncodedId());
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
    Snapshot snapshot = new Snapshot(Thread.currentThread(), logProbe, 10);
    assertTrue(logProbe.fillSnapshot(entryContext, exitContext, null, snapshot));
  }

  @Test
  public void fillSnapshot_shouldSend_evalErrors() {
    LogProbe logProbe = createLog(null).evaluateAt(MethodLocation.EXIT).build();
    CapturedContext entryContext = new CapturedContext();
    LogProbe.LogStatus logStatus = prepareContext(entryContext, logProbe, MethodLocation.ENTRY);
    logStatus.addError(new EvaluationError("expr", "msg1"));
    logStatus.setLogTemplateErrors(true);
    entryContext.addThrowable(new RuntimeException("errorEntry"));
    CapturedContext exitContext = new CapturedContext();
    logStatus = prepareContext(exitContext, logProbe, MethodLocation.EXIT);
    logStatus.addError(new EvaluationError("expr", "msg2"));
    logStatus.setLogTemplateErrors(true);
    exitContext.addThrowable(new RuntimeException("errorExit"));
    Snapshot snapshot = new Snapshot(Thread.currentThread(), logProbe, 10);
    assertTrue(logProbe.fillSnapshot(entryContext, exitContext, null, snapshot));
    assertEquals(2, snapshot.getEvaluationErrors().size());
    assertEquals("msg1", snapshot.getEvaluationErrors().get(0).getMessage());
    assertEquals("msg2", snapshot.getEvaluationErrors().get(1).getMessage());
    assertEquals(
        "errorEntry", snapshot.getCaptures().getEntry().getCapturedThrowable().getMessage());
    assertEquals(
        "errorExit", snapshot.getCaptures().getReturn().getCapturedThrowable().getMessage());
  }

  private LogProbe.Builder createLog(String template) {
    return LogProbe.builder()
        .language(LANGUAGE)
        .probeId(PROBE_ID)
        .template(template, parseTemplate(template));
  }
}
