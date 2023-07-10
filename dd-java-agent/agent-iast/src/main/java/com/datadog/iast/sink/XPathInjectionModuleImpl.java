package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.XPathInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nullable;

public class XPathInjectionModuleImpl extends SinkModuleBase implements XPathInjectionModule {
  @Override
  public void onExpression(@Nullable String expression) {
    if (!canBeTainted(expression)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    checkInjection(span, ctx, VulnerabilityType.InjectionType.XPATH_INJECTION, expression);
  }
}
