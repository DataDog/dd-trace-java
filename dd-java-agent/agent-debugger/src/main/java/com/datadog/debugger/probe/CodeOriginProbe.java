package com.datadog.debugger.probe;

import static com.datadog.debugger.codeorigin.DebuggerConfiguration.isDebuggerEnabled;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_SNAPSHOT_ID;
import static datadog.trace.api.DDTags.DD_STACK_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_STACK_CODE_ORIGIN_TYPE;
import static java.lang.String.format;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.codeorigin.CodeOriginProbeManager;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeOriginProbe extends LogProbe implements ForceMethodInstrumentation {
  private static final Logger LOGGER = LoggerFactory.getLogger(CodeOriginProbe.class);

  private final String signature;

  private final boolean entrySpanProbe;

  private final transient CodeOriginProbeManager probeManager;

  public CodeOriginProbe(
      ProbeId probeId, String signature, Where where, CodeOriginProbeManager probeManager) {
    super(LANGUAGE, probeId, null, where, MethodLocation.EXIT, null, null, true, null, null, null);
    this.signature = signature;
    this.entrySpanProbe = signature != null;
    this.probeManager = probeManager;
  }

  @Override
  public boolean isLineProbe() {
    // these are always method probes even if there is a line number
    return false;
  }

  public boolean isEntrySpanProbe() {
    return entrySpanProbe;
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

    AgentSpan span = AgentTracer.activeSpan();

    if (span == null) {
      LOGGER.debug("Could not find the exit span for probeId {}", id);
      return;
    }
    String snapshotId = null;
    if (isDebuggerEnabled(span)) {
      Snapshot snapshot = createSnapshot();
      if (fillSnapshot(entryContext, exitContext, caughtExceptions, snapshot)) {
        snapshotId = snapshot.getId();
        LOGGER.debug(
            "committing exception probe id={}, snapshot id={}, exception id={}",
            id,
            snapshotId,
            snapshot.getExceptionId());

        commitSnapshot(snapshot, DebuggerAgent.getSink());
      }
    }
    applySpanOriginTags(span, snapshotId);
  }

  private void applySpanOriginTags(AgentSpan span, String snapshotId) {
    List<StackTraceElement> entries = getUserStackFrames();
    if (!entries.isEmpty()) {
      Set<AgentSpan> spans = new LinkedHashSet<>();
      AgentSpan rootSpan = span.getLocalRootSpan() != null ? span.getLocalRootSpan() : span;
      if (rootSpan != null) {
        spans.add(rootSpan);
      }
      int stackIndex = countExistingStacks(span);
      if (entrySpanProbe) {
        spans.add(span);
        StackTraceElement entry = entries.get(0);
        for (AgentSpan s : spans) {
          s.setTag(DDTags.DD_CODE_ORIGIN_FILE, toFileName(entry.getClassName()));
          s.setTag(DDTags.DD_CODE_ORIGIN_METHOD, entry.getMethodName());
          s.setTag(DDTags.DD_CODE_ORIGIN_LINE, entry.getLineNumber());
          s.setTag(DDTags.DD_CODE_ORIGIN_TYPE, entry.getClassName());
          s.setTag(DDTags.DD_CODE_ORIGIN_METHOD_SIGNATURE, signature);
          if (snapshotId != null) {
            s.setTag(DD_CODE_ORIGIN_SNAPSHOT_ID, snapshotId);
          }
        }
      }
      for (AgentSpan s : spans) {
        s.setTag(format(DD_STACK_CODE_ORIGIN_TYPE, stackIndex), entrySpanProbe ? "entry" : "exit");

        for (int i = 0; i < entries.size(); i++) {
          StackTraceElement info = entries.get(i);
          s.setTag(format(DD_STACK_CODE_ORIGIN_FRAME, stackIndex, i, "file"), info.getFileName());
          s.setTag(format(DD_STACK_CODE_ORIGIN_FRAME, stackIndex, i, "line"), info.getLineNumber());
          s.setTag(
              format(DD_STACK_CODE_ORIGIN_FRAME, stackIndex, i, "method"), info.getMethodName());
          s.setTag(format(DD_STACK_CODE_ORIGIN_FRAME, stackIndex, i, "type"), info.getClassName());
          if (i == 0 && snapshotId != null) {
            s.setTag(format(DD_STACK_CODE_ORIGIN_FRAME, stackIndex, i, "snapshot_id"), snapshotId);
          }
        }
      }
    }
  }

  private int countExistingStacks(AgentSpan span) {
    Set<String> keys = span.getLocalRootSpan().getTags().keySet();
    int count = 0;
    while (keys.contains(format(DD_STACK_CODE_ORIGIN_FRAME, count, 0, "file"))) {
      count++;
    }

    return count;
  }

  @Override
  public void buildLocation(InstrumentationResult result) {
    String type = where.getTypeName();
    String method = where.getMethodName();
    if (result != null) {
      type = result.getTypeName();
      method = result.getMethodName();
    }
    // drop line number for exception probe
    this.location = new ProbeLocation(type, method, where.getSourceFile(), null);
  }

  private List<StackTraceElement> getUserStackFrames() {
    ClassNameFiltering classNameFiltering = probeManager.getClassNameFiltering();

    return StackWalkerFactory.INSTANCE.walk(
        stream ->
            stream
                .filter(element -> !classNameFiltering.isExcluded(element.getClassName()))
                .collect(Collectors.toList()));
  }

  private static String toFileName(String className) {
    // this has a number of issues including non-public top level classes and non-Java JVM
    // languages it's here to fulfill the RFC requirement of a file name in the location.
    // This part of the spec needs a little review to find consensus on how to handle this but
    // until then this is here to fill that gap.
    return className.replace('.', '/') + ".java";
  }
}
