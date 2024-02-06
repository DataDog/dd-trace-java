package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.ReflectionInjectionModule;
import javax.annotation.Nullable;

public class ReflectionInjectionModuleImpl extends SinkModuleBase
    implements ReflectionInjectionModule {

  public ReflectionInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onReflection(@Nullable String value) {
    if (!canBeTainted(value)) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    checkInjection(ctx, VulnerabilityType.REFLECTION_INJECTION, value);
  }
}
