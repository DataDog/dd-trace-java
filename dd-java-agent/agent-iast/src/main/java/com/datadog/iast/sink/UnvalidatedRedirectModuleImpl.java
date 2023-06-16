package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.TaintedObject;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class UnvalidatedRedirectModuleImpl extends SinkModuleBase
    implements UnvalidatedRedirectModule {

  private static final String LOCATION_HEADER = "Location";

  @Override
  public void onRedirect(final @Nullable String value) {
    if (!canBeTainted(value)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    checkInjection(span, ctx, VulnerabilityType.UNVALIDATED_REDIRECT, value);
  }

  @Override
  public void onRedirect(@Nonnull String value, @Nonnull String clazz, @Nonnull String method) {
    if (!canBeTainted(value)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    TaintedObject taintedObject = ctx.getTaintedObjects().get(value);
    if (taintedObject == null) {
      return;
    }
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final Evidence evidence = new Evidence(value, taintedObject.getRanges());
    reporter.report(
        span,
        new Vulnerability(
            VulnerabilityType.UNVALIDATED_REDIRECT,
            Location.forSpanAndClassAndMethod(span.getSpanId(), clazz, method),
            evidence));
  }

  @Override
  public void onURIRedirect(@Nullable URI uri) {
    if (uri == null) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    checkInjection(span, ctx, VulnerabilityType.UNVALIDATED_REDIRECT, uri);
  }

  @Override
  public void onHeader(@Nonnull final String name, final String value) {
    if (value != null && LOCATION_HEADER.equalsIgnoreCase(name)) {
      onRedirect(value);
    }
  }
}
