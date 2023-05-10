package com.datadog.iast.sink;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.SsrfModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nullable;

public class SsrfModuleImpl extends SinkModuleBase implements SsrfModule {

  @Override
  public void onURLConnection(@Nullable final Object url) {
    if (url == null) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    checkInjection(span, ctx, VulnerabilityType.SSRF, url);
  }
}
