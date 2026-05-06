package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces the Org Propagation Guard (OPG) on extracted contexts. When an inbound trace carries an
 * Org Propagation Marker (OPM) that does not match the local one, the Datadog-side context
 * (sampling priority, origin, {@code _dd.p.*} propagated tags) is dropped while parent identifiers,
 * baggage, and non-{@code dd} tracestate vendor sections are preserved.
 *
 * <p>Behavior is gated by three configuration knobs:
 *
 * <ul>
 *   <li>{@code DD_TRACE_ORG_GUARD_ENABLED} — master switch; when {@code false} this class is a
 *       no-op.
 *   <li>{@code DD_TRACE_ORG_GUARD_STRICT} — when {@code true}, also enforces when the inbound OPM
 *       is absent.
 *   <li>{@code DD_TRACE_ORG_GUARD_TRUSTED_OPMS} — comma-separated allow-list of inbound OPMs that
 *       should be treated as trusted.
 * </ul>
 *
 * <p>Enforcement never runs when the local OPM is unknown (the agent has not yet reported one).
 */
public final class OrgGuardEnforcer {

  private static final Logger log = LoggerFactory.getLogger(OrgGuardEnforcer.class);

  static final String REASON_MISMATCH = "mismatch";
  static final String REASON_STRICT_MISSING = "strict_missing";

  private final boolean enabled;
  private final boolean strict;
  private final Set<String> trustedOpms;
  private final Supplier<String> localOpmSupplier;
  private final PropagationTags.Factory factory;
  private final HealthMetrics healthMetrics;

  public OrgGuardEnforcer(
      Config config,
      Supplier<String> localOpmSupplier,
      PropagationTags.Factory factory,
      HealthMetrics healthMetrics) {
    this(
        config.isTraceOrgGuardEnabled(),
        config.isTraceOrgGuardStrict(),
        config.getTraceOrgGuardTrustedOpms(),
        localOpmSupplier,
        factory,
        healthMetrics);
  }

  // Visible for testing.
  OrgGuardEnforcer(
      boolean enabled,
      boolean strict,
      Set<String> trustedOpms,
      Supplier<String> localOpmSupplier,
      PropagationTags.Factory factory,
      HealthMetrics healthMetrics) {
    this.enabled = enabled;
    this.strict = strict;
    this.trustedOpms = trustedOpms;
    this.localOpmSupplier = localOpmSupplier;
    this.factory = factory;
    this.healthMetrics = healthMetrics;
  }

  /**
   * Returns {@code extracted} unchanged unless OPG enforcement applies, in which case it returns a
   * fresh {@link ExtractedContext} with the Datadog-side context dropped.
   */
  public TagContext maybeStrip(TagContext extracted) {
    if (!enabled || !(extracted instanceof ExtractedContext)) {
      return extracted;
    }
    ExtractedContext ctx = (ExtractedContext) extracted;
    String localOpm = localOpmSupplier.get();
    if (localOpm == null) {
      // We don't know our own OPM yet — never enforce.
      return extracted;
    }
    CharSequence inboundCs = ctx.getPropagationTags().getOrgPropagationMarker();
    String inbound = inboundCs == null ? null : inboundCs.toString();

    if (inbound == null) {
      if (!strict) {
        return extracted;
      }
      return strip(ctx, REASON_STRICT_MISSING, localOpm, null);
    }
    if (localOpm.equals(inbound) || trustedOpms.contains(inbound)) {
      return extracted;
    }
    return strip(ctx, REASON_MISMATCH, localOpm, inbound);
  }

  private ExtractedContext strip(
      ExtractedContext ctx, String reason, String localOpm, String inbound) {
    log.debug(
        "OPG enforcement: dropping dd context (reason={}, inbound={}, local={})",
        reason,
        inbound,
        localOpm);
    healthMetrics.onOrgGuardEnforce(reason);

    PropagationTags stripped = factory.emptyW3C(ctx.getPropagationTags().getW3CTracestate());
    return new ExtractedContext(
        ctx.getTraceId(),
        ctx.getSpanId(),
        PrioritySampling.UNSET,
        /* origin */ null,
        ctx.getEndToEndStartTime(),
        ctx.getBaggage(),
        ctx.getTags(),
        /* httpHeaders */ null,
        stripped,
        ctx.getTraceConfig(),
        ctx.getPropagationStyle());
  }
}
