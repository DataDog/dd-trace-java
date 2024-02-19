package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.util.Iterators;
import datadog.trace.api.iast.sink.LdapInjectionModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LdapInjectionModuleImpl extends SinkModuleBase implements LdapInjectionModule {

  public LdapInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onDirContextSearch(
      @Nullable final String name,
      @Nonnull final String filterExpr,
      @Nullable final Object[] filterArgs) {
    if (!canBeTainted(name) && !canBeTainted(filterExpr) && filterArgs == null) {
      return;
    }
    checkInjection(
        VulnerabilityType.LDAP_INJECTION,
        Iterators.join(Iterators.of(name, filterExpr), Iterators.of(filterArgs)));
  }
}
