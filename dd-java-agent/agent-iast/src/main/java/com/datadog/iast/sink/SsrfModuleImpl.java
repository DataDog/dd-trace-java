package com.datadog.iast.sink;

import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nullable;

public class SsrfModuleImpl extends SinkModuleBase implements SsrfModule {

  public SsrfModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onURLConnection(@Nullable final Object url) {
    if (url == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    checkInjection(ctx, VulnerabilityType.SSRF, url);
  }

  /**
   * if the host or the uri are tainted, we report the url as tainted as well a new range is created
   * covering all the value string in order to simplify the algorithm
   *
   * @param value
   * @param host
   * @param uri
   */
  @Override
  public void onURLConnection(@Nullable String value, @Nullable Object host, @Nullable Object uri) {
    if (value == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    TaintedObject taintedObject = getTaintedObject(to, host, uri);
    if (taintedObject == null) {
      return;
    }
    Range[] ranges =
        Ranges.getNotMarkedRanges(taintedObject.getRanges(), VulnerabilityType.SSRF.mark());
    if (ranges == null || ranges.length == 0) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    Source source = Ranges.highestPriorityRange(ranges).getSource();
    final Evidence result =
        new Evidence(value, new Range[] {new Range(0, value.length(), source, NOT_MARKED)});
    report(span, VulnerabilityType.SSRF, result);
  }

  @Nullable
  private TaintedObject getTaintedObject(
      final TaintedObjects to, @Nullable final Object host, @Nullable final Object uri) {
    TaintedObject taintedObject = null;
    if (uri != null) {
      taintedObject = to.get(uri);
    }
    if (taintedObject == null && host != null) {
      taintedObject = to.get(host);
    }
    return taintedObject;
  }
}
