package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.LdapInjectionModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LdapInjectionModuleImpl extends SinkModuleBase implements LdapInjectionModule {

  public LdapInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @SuppressWarnings("unchecked")
  @Override
  public void onDirContextSearch(
      @Nullable final String name,
      @Nonnull final String filterExpr,
      @Nullable final Object[] filterArgs) {
    if (!canBeTainted(name) && !canBeTainted(filterExpr) && filterArgs == null) {
      return;
    }
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      LOG.debug("No IastRequestContext available");
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    checkInjection(
        VulnerabilityType.LDAP_INJECTION,
        rangesProviderFor(to, name),
        rangesProviderFor(to, filterExpr),
        rangesProviderFor(to, filterArgs));
  }
}
