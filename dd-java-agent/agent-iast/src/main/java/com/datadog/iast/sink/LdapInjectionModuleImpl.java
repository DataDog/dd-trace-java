package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.sink.LdapInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LdapInjectionModuleImpl extends SinkModuleBase implements LdapInjectionModule {

  @SuppressWarnings("unchecked")
  @Override
  public void onDirContextSearch(
      @Nullable final String name,
      @Nonnull final String filterExpr,
      @Nullable final Object[] filterArgs) {
    if (!canBeTainted(name) && !canBeTainted(filterExpr) && filterArgs == null) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      LOG.debug("No IastRequestContext available");
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    checkInjection(
        span,
        VulnerabilityType.LDAP_INJECTION,
        rangesProviderFor(to, name),
        rangesProviderFor(to, filterExpr),
        rangesProviderFor(to, filterArgs));
  }
}
