package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.allRangesFromHeader;
import static com.datadog.iast.taint.Tainteds.canBeTainted;
import static com.datadog.iast.util.HttpHeader.LOCATION;
import static com.datadog.iast.util.HttpHeader.REFERER;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import com.datadog.iast.taint.Ranges;
import com.datadog.iast.taint.TaintedObject;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.URI;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class UnvalidatedRedirectModuleImpl extends SinkModuleBase
    implements UnvalidatedRedirectModule {

  public UnvalidatedRedirectModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onRedirect(final @Nullable String value) {
    if (!canBeTainted(value)) {
      return;
    }
    checkUnvalidatedRedirect(value);
  }

  @Override
  public void onRedirect(@Nonnull String value, @Nonnull String clazz, @Nonnull String method) {
    if (!canBeTainted(value)) {
      return;
    }
    checkUnvalidatedRedirect(value, clazz, method);
  }

  @Override
  public void onURIRedirect(@Nullable URI uri) {
    if (uri == null) {
      return;
    }
    checkUnvalidatedRedirect(uri);
  }

  @Override
  public void onHeader(@Nonnull final String name, @Nullable final String value) {
    if (value != null && LOCATION.matches(name)) {
      onRedirect(value);
    }
  }

  private void checkUnvalidatedRedirect(@Nonnull final Object value) {
    checkUnvalidatedRedirect(value, null, null);
  }

  private void checkUnvalidatedRedirect(
      @Nonnull final Object value, @Nullable final String clazz, @Nullable final String method) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    TaintedObject taintedObject = to.get(value);
    if (taintedObject == null) {
      return;
    }
    if (allRangesFromHeader(REFERER, taintedObject.getRanges())) {
      return;
    }
    Range[] notMarkedRanges =
        Ranges.getNotMarkedRanges(
            taintedObject.getRanges(), VulnerabilityType.UNVALIDATED_REDIRECT.mark());
    if (notMarkedRanges == null || notMarkedRanges.length == 0) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    final Evidence evidence = new Evidence(value.toString(), notMarkedRanges);
    if (clazz != null && method != null) {
      reporter.report(
          span,
          new Vulnerability(
              VulnerabilityType.UNVALIDATED_REDIRECT,
              Location.forSpanAndClassAndMethod(span, clazz, method),
              evidence));
    } else {
      report(span, VulnerabilityType.UNVALIDATED_REDIRECT, evidence);
    }
  }
}
