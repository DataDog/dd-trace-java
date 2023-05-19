package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.URI;
import javax.annotation.Nullable;

public class UnvalidatedRedirectModuleImpl extends SinkModuleBase
    implements UnvalidatedRedirectModule {

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
  public void onHeader(String name, String value) {
    if ("Location".equalsIgnoreCase(name)) {
      onRedirect(value);
    }
  }
}
