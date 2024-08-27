package com.datadog.debugger.probe;

import static com.datadog.debugger.snapshot.SpanDebug.ALL_FRAMES;
import static com.datadog.debugger.snapshot.SpanDebug.CAPTURE_ALL_PROBES;
import static com.datadog.debugger.snapshot.SpanDebug.CAPTURE_ORIGIN_FRAMES;
import static com.datadog.debugger.snapshot.SpanDebug.ORIGIN_FRAME_ONLY;
import static com.datadog.debugger.snapshot.SpanDebug.isSpanDebugEnabled;
import static datadog.trace.api.DDTags.DD_EXIT_LOCATION_SNAPSHOT_ID;
import static java.lang.String.format;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.snapshot.SpanDebuggerProbeManager;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedContext.CapturedThrowable;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpanDebuggerProbe extends LogProbe implements ForceMethodInstrumentation {
  private static final Logger LOGGER = LoggerFactory.getLogger(SpanDebuggerProbe.class);

  private final String signature;

  private final boolean entrySpanProbe;

  private final transient SpanDebuggerProbeManager probeManager;

  public SpanDebuggerProbe(
      ProbeId probeId, String signature, Where where, SpanDebuggerProbeManager probeManager) {
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

    AgentSpan span = findCorrectSpan(AgentTracer.activeSpan());

    if (span == null) {
      LOGGER.debug("Could not find the exit span for probeId {}", id);
      return;
    }
    applySpanOriginTags(span);
    commitSnapshot(span, entryContext, exitContext, caughtExceptions);
  }

  private void commitSnapshot(
      AgentSpan span,
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedThrowable> caughtExceptions) {
    if (isSpanDebugEnabled(span, CAPTURE_ORIGIN_FRAMES, CAPTURE_ALL_PROBES)) {
      String key =
          entrySpanProbe ? DDTags.DD_ENTRY_LOCATION_SNAPSHOT_ID : DD_EXIT_LOCATION_SNAPSHOT_ID;
      Snapshot snapshot = createSnapshot();
      if (fillSnapshot(entryContext, exitContext, caughtExceptions, snapshot)) {
        LOGGER.debug(
            "committing exception probe id={}, snapshot id={}, exception id={}",
            id,
            snapshot.getId(),
            snapshot.getExceptionId());

        span.setTag(key, snapshot.getId());
        if (entrySpanProbe && span.getLocalRootSpan() != null) {
          span.getLocalRootSpan().setTag(key, snapshot.getId());
        }
        commitSnapshot(snapshot, DebuggerAgent.getSink());
      }
    }
  }

  private AgentSpan findCorrectSpan(AgentSpan span) {
    if (entrySpanProbe) {
      return span;
    }
    if (span.getLocalRootSpan().context() instanceof DDSpanContext) {
      DDSpanContext rootContext = (DDSpanContext) span.getLocalRootSpan().context();

      Map<String, Object> rootMetaStruct = rootContext.getMetaStruct();
      Object object = rootMetaStruct.get(probeId.getId());
      rootContext.setMetaStruct(probeId.getId(), null);
      return object instanceof AgentSpan ? (AgentSpan) object : null;
    }
    // just don't record the exit info if we can't find the correct span
    return null;
  }

  private void applySpanOriginTags(AgentSpan span) {
    if (isSpanDebugEnabled(span, ORIGIN_FRAME_ONLY, ALL_FRAMES)) {
      List<StackTraceElement> entries = getUserStackFrames();
      if (!entries.isEmpty()) {
        if (entrySpanProbe) {
          StackTraceElement entry = entries.get(0);
          Set<AgentSpan> spans = new LinkedHashSet<>();
          spans.add(span);
          AgentSpan rootSpan = span.getLocalRootSpan();
          if (rootSpan != null && rootSpan.getTags().get(DDTags.DD_ENTRY_LOCATION_FILE) == null) {
            spans.add(rootSpan);
          }
          for (AgentSpan s : spans) {
            s.setTag("_dd.di.has_code_location", true);
            s.setTag(DDTags.DD_ENTRY_LOCATION_FILE, toFileName(entry.getClassName()));
            s.setTag(DDTags.DD_ENTRY_METHOD, entry.getMethodName());
            s.setTag(DDTags.DD_ENTRY_LINE, entry.getLineNumber());
            s.setTag(DDTags.DD_ENTRY_TYPE, entry.getClassName());
            s.setTag(DDTags.DD_ENTRY_METHOD_SIGNATURE, signature);
          }
        } else {
          span.setTag("_dd.di.has_code_location", true);
          if (isSpanDebugEnabled(span, ORIGIN_FRAME_ONLY)) {
            entries = entries.subList(0, 1);
          }
          for (int i = 0; i < entries.size(); i++) {
            StackTraceElement element = entries.get(i);
            span.setTag(
                format(DDTags.DD_EXIT_LOCATION_FILE, i), toFileName(element.getClassName()));
            span.setTag(format(DDTags.DD_EXIT_LOCATION_METHOD, i), element.getMethodName());
            span.setTag(format(DDTags.DD_EXIT_LOCATION_LINE, i), element.getLineNumber());
            span.setTag(format(DDTags.DD_EXIT_LOCATION_TYPE, i), element.getClassName());
          }
        }
      }
    }
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
    List<StackTraceElement> entries =
        StackWalkerFactory.INSTANCE.walk(
            stream ->
                stream
                    .filter(element -> !classNameFiltering.isExcluded(element.getClassName()))
                    .collect(Collectors.toList()));

    return entries;
  }

  private static String toFileName(String className) {
    // this has a number of issues including non-public top level classes and non-Java JVM
    // languages it's here to fulfill the RFC requirement of a file name in the location.
    // This part of the spec needs a little review to find consensus on how to handle this but
    // until then this is here to fill that gap.
    return className.replace('.', '/') + ".java";
  }
}
