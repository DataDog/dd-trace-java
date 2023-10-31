package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import datadog.trace.api.iast.sink.XssModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class XssModuleImpl extends SinkModuleBase implements XssModule {

  public XssModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onXss(@Nonnull String s) {
    if (!canBeTainted(s)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInjection(span, VulnerabilityType.XSS, s);
  }

  @Override
  public void onXss(@Nonnull String s, @Nonnull String clazz, @Nonnull String method) {
    if (!canBeTainted(s)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    TaintedObject taintedObject = taintedObjects.get(s);
    if (taintedObject == null) {
      return;
    }
    Range[] notMarkedRanges =
        Ranges.getNotMarkedRanges(taintedObject.getRanges(), VulnerabilityType.XSS.mark());
    if (notMarkedRanges == null || notMarkedRanges.length == 0) {
      return;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final Evidence evidence = new Evidence(s, notMarkedRanges);
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.XSS,
            Location.forSpanAndClassAndMethod(span, clazz, method),
            evidence));
  }

  @Override
  public void onXss(@Nonnull char[] array) {
    if (array == null || array.length == 0) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInjection(span, VulnerabilityType.XSS, array);
  }

  @Override
  public void onXss(@Nonnull String format, @Nullable Object[] args) {
    if ((args == null || args.length == 0) && !canBeTainted(format)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInjection(
        span,
        VulnerabilityType.XSS,
        rangesProviderFor(taintedObjects, format),
        rangesProviderFor(taintedObjects, args));
  }

  @Override
  public void onXss(@Nonnull CharSequence s, @Nullable String file, int line) {
    if (!canBeTainted(s) || file == null || file.isEmpty()) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    TaintedObject taintedObject = taintedObjects.get(s);
    if (taintedObject == null) {
      return;
    }
    Range[] notMarkedRanges =
        Ranges.getNotMarkedRanges(taintedObject.getRanges(), VulnerabilityType.XSS.mark());
    if (notMarkedRanges == null || notMarkedRanges.length == 0) {
      return;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final Evidence evidence = new Evidence(s.toString(), notMarkedRanges);
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.XSS, Location.forSpanAndFileAndLine(span, file, line), evidence));
  }
}
