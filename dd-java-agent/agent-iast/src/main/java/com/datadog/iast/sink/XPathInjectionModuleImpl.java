package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.XPathInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nullable;

public class XPathInjectionModuleImpl extends SinkModuleBase implements XPathInjectionModule {

  public XPathInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onExpression(@Nullable String expression) {
    if (!canBeTainted(expression)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    checkInjection(span, VulnerabilityType.InjectionType.XPATH_INJECTION, expression);
  }
}
