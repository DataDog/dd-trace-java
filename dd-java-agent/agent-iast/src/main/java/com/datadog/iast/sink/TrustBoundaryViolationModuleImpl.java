package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.VulnerabilityType;
import datadog.instrument.utils.ClassNameTrie;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import java.util.Map;
import javax.annotation.Nonnull;

public class TrustBoundaryViolationModuleImpl extends SinkModuleBase
    implements TrustBoundaryViolationModule {

  private static final ClassNameTrie ALLOWED_COLLECTION_PKGS;

  static {
    final ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
    builder.put("java.util.*", 1);
    builder.put("org.apache.commons.collections*", 1);
    builder.put("com.google.common.collect*", 1);
    builder.put("org.springframework.web.servlet.FlashMap", 1);
    ALLOWED_COLLECTION_PKGS = builder.buildTrie();
  }

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
    if ((Iterable.class.isAssignableFrom(clazz) || Map.class.isAssignableFrom(clazz))) {
      final String className = clazz.getName();
      return ALLOWED_COLLECTION_PKGS.apply(className) > 0;
    }
    return false;
  }
}
