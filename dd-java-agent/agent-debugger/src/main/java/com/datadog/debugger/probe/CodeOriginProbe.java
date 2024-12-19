package com.datadog.debugger.probe;

import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_TYPE;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import com.datadog.debugger.instrumentation.BasicProbeInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.instrumentation.MethodInfo;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedContext.CapturedThrowable;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeOriginProbe extends ProbeDefinition {
  private static final Logger LOGGER = LoggerFactory.getLogger(CodeOriginProbe.class);

  private final boolean entrySpanProbe;

  private String signature;

  public CodeOriginProbe(ProbeId probeId, boolean entry, Where where) {
    super(LANGUAGE, probeId, (Tag[]) null, where, MethodLocation.ENTRY);
    this.entrySpanProbe = entry;
  }

  @Override
  public Status instrument(
      MethodInfo methodInfo, List<DiagnosticMessage> diagnostics, List<ProbeId> probeIds) {
    return new BasicProbeInstrumentor(this, methodInfo, diagnostics, probeIds).instrument();
  }

  @Override
  public boolean isLineProbe() {
    // these are always method probes even if there is a line number
    return false;
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedThrowable> caughtExceptions) {
    recordCodeOrigin();
  }

  private void recordCodeOrigin() {
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      LOGGER.debug("Could not find the span for probeId {}", id);
      return;
    }
    List<AgentSpan> agentSpans =
        entrySpanProbe ? asList(span, span.getLocalRootSpan()) : singletonList(span);

    for (AgentSpan s : agentSpans) {
      s.setTag(DD_CODE_ORIGIN_TYPE, entrySpanProbe ? "entry" : "exit");
      int i = 0;
      s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "file"), location.getFile());
      s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "method"), location.getMethod());
      s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "line"), location.getLines().get(0));
      s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "type"), location.getType());
      s.setTag(format(DD_CODE_ORIGIN_FRAME, i, "signature"), signature);
    }
  }

  public boolean entrySpanProbe() {
    return entrySpanProbe;
  }

  @Override
  public void buildLocation(InstrumentationResult result) {
    String type = where.getTypeName();
    String method = where.getMethodName();
    List<String> lines = null;

    String file = where.getSourceFile();

    if (result != null) {
      type = result.getTypeName();
      method = result.getMethodName();
      if (result.getMethodStart() != -1) {
        lines = singletonList(String.valueOf(result.getMethodStart()));
      }
      if (file == null) {
        file = result.getSourceFileName();
      }
      signature = result.getMethodSignature();
    }
    this.location = new ProbeLocation(type, method, file, lines);
  }
}
