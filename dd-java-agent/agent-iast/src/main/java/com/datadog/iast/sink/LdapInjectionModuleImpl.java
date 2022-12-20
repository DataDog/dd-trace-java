package com.datadog.iast.sink;

import static com.datadog.iast.taint.Ranges.rangesProviderFor;
import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.sink.LdapInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class LdapInjectionModuleImpl extends SinkModuleBase implements LdapInjectionModule {

  @Override
  public void onDirContextSearch(
      @Nullable final String name,
      @Nonnull final String filterExpr,
      @Nullable final Object[] filterArgs) {
    List<String> elements = null;
    if (canBeTainted(name)) {
      elements = new ArrayList<>();
      elements.add(name);
    }
    if (canBeTainted(filterExpr)) {
      elements = getElements(elements);
      elements.add(filterExpr);
    }
    if (filterArgs != null) {
      for (final Object filterArg : filterArgs) {
        if (filterArg instanceof String) {
          final String stringArg = (String) filterArg;
          if (canBeTainted(stringArg)) {
            elements = getElements(elements);
            elements.add(stringArg);
          }
        }
      }
    }
    if (elements == null || elements.isEmpty()) {
      LOG.debug("there ara no elements that can be tainted");
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get();
    if (ctx == null) {
      LOG.debug("No IastRequestContext available");
      return;
    }
    final TaintedObjects to = ctx.getTaintedObjects();
    checkInjection(span, VulnerabilityType.LDAP_INJECTION, rangesProviderFor(to, elements));
  }

  private static List<String> getElements(@Nullable final List<String> elements) {
    if (elements == null) {
      return new ArrayList<>();
    }
    return elements;
  }
}
