package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.XPathInjectionModule;
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
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    checkInjection(VulnerabilityType.XPATH_INJECTION, expression);
  }
}
