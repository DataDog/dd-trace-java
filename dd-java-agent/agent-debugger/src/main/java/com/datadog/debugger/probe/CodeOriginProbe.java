package com.datadog.debugger.probe;

import static com.datadog.debugger.codeorigin.DebuggerConfiguration.isDebuggerEnabled;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_TYPE;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeOriginProbe extends LogProbe implements ForceMethodInstrumentation {
  private static final Logger LOGGER = LoggerFactory.getLogger(CodeOriginProbe.class);

  private final String signature;

  private final boolean entrySpanProbe;

  public CodeOriginProbe(ProbeId probeId, String signature, Where where) {
    super(LANGUAGE, probeId, null, where, MethodLocation.EXIT, null, null, true, null, null, null);
    this.signature = signature;
    this.entrySpanProbe = signature != null;
  }

  @Override
  public boolean isLineProbe() {
    // these are always method probes even if there is a line number
    return false;
  }

  @Override
  public void evaluate(
      CapturedContext context, CapturedContext.Status status, MethodLocation methodLocation) {
    if (!MethodLocation.isSame(methodLocation, getEvaluateAt())) {
      return;
    }

    super.evaluate(context, status, methodLocation);
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions) {

    AgentSpan span = findSpan(AgentTracer.activeSpan());

    if (span == null) {
      LOGGER.debug("Could not find the span for probeId {}", id);
      return;
    }
    String snapshotId = null;
    if (isDebuggerEnabled(span)) {
      Snapshot snapshot = createSnapshot();
      if (fillSnapshot(entryContext, exitContext, caughtExceptions, snapshot)) {
        snapshotId = snapshot.getId();
        LOGGER.debug("committing code origin probe id={}, snapshot id={}", id, snapshotId);
        commitSnapshot(snapshot, DebuggerAgent.getSink());
      }
    }
    applySpanOriginTags(span, snapshotId);
    DebuggerAgent.getSink().getProbeStatusSink().addEmitting(probeId);
    span.getLocalRootSpan().setTag(getId(), (String) null); // clear possible span reference
  }

  private void applySpanOriginTags(AgentSpan span, String snapshotId) {
    List<StackTraceElement> entries = getUserStackFrames();
    List<AgentSpan> agentSpans =
        entrySpanProbe ? asList(span, span.getLocalRootSpan()) : singletonList(span);

    for (AgentSpan s : agentSpans) {
      s.setTag(DD_CODE_ORIGIN_TYPE, entrySpanProbe ? "entry" : "exit");

      for (int i = 0; i < entries.size(); i++) {
        StackTraceElement info = entries.get(i);
        s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "file"), info.getFileName());
        s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "method"), info.getMethodName());
        s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "line"), info.getLineNumber());
        s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "type"), info.getClassName());
        if (i == 0 && signature != null) {
          s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "signature"), signature);
        }
        if (i == 0 && snapshotId != null) {
          s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "snapshot_id"), snapshotId);
        }
      }
    }
  }

  public boolean entrySpanProbe() {
    return entrySpanProbe;
  }

  /** look "back" to find exit spans that may have already come and gone */
  private AgentSpan findSpan(AgentSpan candidate) {
    AgentSpan span = candidate;
    AgentSpan localRootSpan = candidate.getLocalRootSpan();
    if (localRootSpan.getTag(getId()) != null) {
      span = (AgentSpan) localRootSpan.getTag(getId());
    }
    return span;
  }

  @Override
  public void buildLocation(InstrumentationResult result) {
    String type = where.getTypeName();
    String method = where.getMethodName();
    if (result != null) {
      type = result.getTypeName();
      method = result.getMethodName();
    }
    // drop line number for code origin probe
    this.location = new ProbeLocation(type, method, where.getSourceFile(), null);
  }

  private List<StackTraceElement> getUserStackFrames() {
    return StackWalkerFactory.INSTANCE.walk(
        stream ->
            stream
                .filter(element -> !DebuggerContext.isClassNameExcluded(element.getClassName()))
                .collect(Collectors.toList()));
  }
}
