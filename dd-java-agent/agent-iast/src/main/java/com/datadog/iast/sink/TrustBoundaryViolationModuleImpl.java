package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;

public class TrustBoundaryViolationModuleImpl extends SinkModuleBase
    implements TrustBoundaryViolationModule {

  public TrustBoundaryViolationModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onSessionValue(@Nonnull String name, Object value) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }
    checkInjection(span, VulnerabilityType.TRUST_BOUNDARY_VIOLATION, name);
    if (value != null) {
      checkInjectionDeeply(span, VulnerabilityType.TRUST_BOUNDARY_VIOLATION, value);
    }
  }
}
