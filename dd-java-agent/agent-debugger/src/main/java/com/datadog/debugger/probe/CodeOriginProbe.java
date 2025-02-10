package com.datadog.debugger.probe;

import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_FRAME;
import static datadog.trace.api.DDTags.DD_CODE_ORIGIN_TYPE;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.instrumentation.CodeOriginInstrumentor;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.instrumentation.InstrumentationResult.Status;
import com.datadog.debugger.instrumentation.MethodInfo;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedContext.CapturedThrowable;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
    return new CodeOriginInstrumentor(this, methodInfo, diagnostics, probeIds).instrument();
  }

  @Override
  public void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedThrowable> caughtExceptions) {
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      LOGGER.debug("Could not find the span for probeId {}", id);
      return;
    }
    List<AgentSpan> agentSpans =
        entrySpanProbe ? asList(span, span.getLocalRootSpan()) : singletonList(span);

    for (AgentSpan s : agentSpans) {
      if (s.getTag(DD_CODE_ORIGIN_TYPE) == null) {
        s.setTag(DD_CODE_ORIGIN_TYPE, entrySpanProbe ? "entry" : "exit");
        s.setTag(format(DD_CODE_ORIGIN_FRAME, 0, "file"), location.getFile());
        s.setTag(format(DD_CODE_ORIGIN_FRAME, 0, "method"), location.getMethod());
        s.setTag(format(DD_CODE_ORIGIN_FRAME, 0, "line"), location.getLines().get(0));
        s.setTag(format(DD_CODE_ORIGIN_FRAME, 0, "type"), location.getType());
        s.setTag(format(DD_CODE_ORIGIN_FRAME, 0, "signature"), signature);
      }
    }
  }

  public boolean entrySpanProbe() {
    return entrySpanProbe;
  }

  @Override
  public void buildLocation(MethodInfo methodInfo) {
    String type = where.getTypeName();
    String method = where.getMethodName();
    List<String> lines = null;

    String file = where.getSourceFile();

    if (methodInfo != null) {
      type = methodInfo.getTypeName();
      method = methodInfo.getMethodName();
      if (methodInfo.getMethodStart() != -1) {
        lines = singletonList(String.valueOf(methodInfo.getMethodStart()));
      }
      if (file == null) {
        file = methodInfo.getSourceFileName();
      }
      signature = methodInfo.getSignature();
    }
    this.location = new ProbeLocation(type, method, file, lines);
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    CodeOriginProbe that = (CodeOriginProbe) o;
    return Objects.equals(language, that.language)
        && Objects.equals(id, that.id)
        && version == that.version
        && Arrays.equals(tags, that.tags)
        && Objects.equals(tagMap, that.tagMap)
        && Objects.equals(where, that.where)
        && Objects.equals(evaluateAt, that.evaluateAt)
        && entrySpanProbe == that.entrySpanProbe;
  }

  @Generated
  @Override
  public int hashCode() {
    int result = Objects.hash(language, id, version, tagMap, where, evaluateAt, entrySpanProbe);
    result = 31 * result + Arrays.hashCode(tags);
    return result;
  }

  @Override
  public String toString() {
    return "CodeOriginProbe{"
        + "id='"
        + id
        + '\''
        + ", version="
        + version
        + ", tags="
        + Arrays.toString(tags)
        + ", where="
        + where
        + ", evaluateAt="
        + evaluateAt
        + ", entrySpanProbe="
        + entrySpanProbe
        + "} ";
  }
}
