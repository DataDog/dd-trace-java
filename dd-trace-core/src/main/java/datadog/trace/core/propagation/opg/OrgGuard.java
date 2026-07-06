package datadog.trace.core.propagation.opg;

import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.PropagationTags;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Single entry point for the Org Propagation Guard (OPG). Owns the gating decision and constructs
 * the extract-side and inject-side decorators when OPG is enabled. When disabled, {@link
 * #decorateExtractor} and {@link #decorateInjector} return their argument unchanged so the
 * propagation chain pays zero overhead and any inbound OPM passes through the codec layer
 * untouched.
 */
public final class OrgGuard {

  @Nullable private final OrgGuardEnforcer enforcer;

  public static OrgGuard create(
      Config config,
      Supplier<String> localOpmSupplier,
      PropagationTags.Factory propagationTagsFactory,
      HealthMetrics healthMetrics) {
    if (!config.isTraceOrgGuardEnabled()) {
      return new OrgGuard(null);
    }
    return new OrgGuard(
        new OrgGuardEnforcer(config, localOpmSupplier, propagationTagsFactory, healthMetrics));
  }

  private OrgGuard(@Nullable OrgGuardEnforcer enforcer) {
    this.enforcer = enforcer;
  }

  public HttpCodec.Extractor decorateExtractor(HttpCodec.Extractor delegate) {
    return enforcer == null ? delegate : new OrgGuardEnforcingExtractor(delegate, enforcer);
  }

  public HttpCodec.Injector decorateInjector(HttpCodec.Injector delegate) {
    return enforcer == null
        ? delegate
        : new OpmStampingInjector(delegate, enforcer.localOpmSupplier());
  }

  /** Reason an extracted Datadog context was dropped by the OPG enforcer. */
  public enum Reason {
    MISMATCH("mismatch"),
    STRICT_MISSING("strict_missing");

    private final String tag;

    Reason(String tag) {
      this.tag = tag;
    }

    /** Statsd tag value for the {@code reason} dimension on {@code org_guard.enforce} metrics. */
    public String tag() {
      return tag;
    }
  }
}
