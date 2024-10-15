package com.datadog.debugger.probe;

import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_ENTRY;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAMES;
import static java.lang.String.format;
import static java.util.Map.Entry.comparingByKey;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.instrumentation.CapturedContextInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.instrumentation.MethodInfo;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedContext.CapturedThrowable;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FrameProbe extends ProbeDefinition {
  public FrameProbe(ProbeId probeId, Where where, MethodLocation evaluateAt) {
    super(LANGUAGE, probeId, (Tag[]) null, where, evaluateAt);
    System.out.printf("****** FrameProbe.FrameProbe probeId = %s%n", probeId);
  }

  @Override
  public void commit(CapturedContext lineContext, int line) {
    recordLocation();
    AgentSpan span = AgentTracer.activeSpan();

    Map<String, Object> baggage = ((DDSpan) span).getTags();
    baggage.entrySet().stream()
        .filter(entry1 -> entry1.getKey().startsWith(DD_CODE_ORIGIN_FRAMES))
        .sorted(comparingByKey())
        .collect(Collectors.toList())
        .forEach(entry -> System.out.println("###### FrameProbe.commit entry = " + entry));
    DebuggerAgent.getSink().getProbeStatusSink().addEmitting(probeId);
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedThrowable> caughtExceptions) {

    //    System.out.printf(
    //        "****** METHOD PROBE FrameProbe.commit entryContext = %s, exitContext = %s,
    // caughtExceptions = %s%n",
    //        entryContext, exitContext, caughtExceptions);
  }

  private void recordLocation() {
    AgentSpan span = AgentTracer.activeSpan();
    System.out.println("****** FrameProbe.recordLocation span.getSpanId() = " + span.getSpanId());

    int index = findIndex(span);
    span.setTag(format(DD_CODE_ORIGIN_ENTRY, index, "file"), where.getSourceFile());
    span.setTag(format(DD_CODE_ORIGIN_ENTRY, index, "method"), where.getMethodName());
    span.setTag(format(DD_CODE_ORIGIN_ENTRY, index, "line"), where.getLines()[0]);
    span.setTag(format(DD_CODE_ORIGIN_ENTRY, index, "type"), where.getTypeName());
  }

  private int findIndex(AgentSpan span) {
    int index = 0;
    //    System.out.printf("****** FrameProbe.findIndex span = %s%n", span);
    while (span.getBaggageItem(format(DD_CODE_ORIGIN_ENTRY, index, "file")) != null) {
      System.out.printf("frame entry %d found. checking the next slot%n", index);
      index++;
    }

    return index;
  }

  @Override
  public Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<ProbeId> probeIds) {
    return new CapturedContextInstrumentor(
            this, methodInfo, diagnostics, probeIds, false, Limits.DEFAULT)
        .instrument();
  }

  @Override
  public String toString() {
    return String.format(
        "FrameProbe{id='%s', where=%s:%s:%s}",
        id, where.getTypeName(), where.getMethodName(), where.getLines()[0]);
  }
}
