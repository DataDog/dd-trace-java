package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;

public class TrustBoundaryViolationModuleImpl extends SinkModuleBase
    implements TrustBoundaryViolationModule {

  private static final List<String> ALLOWED_COLLECTION_PKGS =
      Arrays.asList("java.util", "org.apache.commons.collections", "com.google.common.collect");

  public TrustBoundaryViolationModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onSessionValue(@Nonnull String name, Object value) {
    final IastContext ctx = IastContext.Provider.get();
    if (ctx == null) {
      return;
    }
    checkInjection(VulnerabilityType.TRUST_BOUNDARY_VIOLATION, name);
    if (value != null) {
      checkInjectionDeeply(
          VulnerabilityType.TRUST_BOUNDARY_VIOLATION,
          value,
          TrustBoundaryViolationModuleImpl::visitClass);
    }
  }

  /**
   * Any potential object can be stored in the session (including lazy ones) so we must be very
   * careful and ensure that we don't potentially cause any harm by limiting ourselves to well known
   * types.
   */
  private static boolean visitClass(final Class<?> clazz) {
    final String className = clazz.getName();
    for (final String pkg : ALLOWED_COLLECTION_PKGS) {
      if (className.startsWith(pkg)) {
        return true;
      }
    }
    return false;
  }
}
