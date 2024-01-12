package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import javax.annotation.Nonnull;

public class TrustBoundaryViolationModuleImpl extends SinkModuleBase
    implements TrustBoundaryViolationModule {

  public TrustBoundaryViolationModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onSessionValue(@Nonnull String name, Object value) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    checkInjection(ctx, VulnerabilityType.TRUST_BOUNDARY_VIOLATION, name);
    if (value != null) {
      checkInjectionDeeply(ctx, VulnerabilityType.TRUST_BOUNDARY_VIOLATION, value);
    }
  }
}
