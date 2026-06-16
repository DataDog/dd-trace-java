package datadog.trace.core.propagation.opg;

import datadog.trace.api.Config;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
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
 * <p>The master {@code DD_TRACE_ORG_GUARD_ENABLED} switch is handled at the {@link OrgGuard}
 * factory; when disabled this class is never instantiated. Two configuration knobs shape behavior
 * once enabled:
 *
 * <ul>
 *   <li>{@code DD_TRACE_ORG_GUARD_STRICT} — when {@code true}, also enforces when the inbound OPM
 *       is absent.
 *   <li>{@code DD_TRACE_ORG_GUARD_TRUSTED_OPMS} — comma-separated allow-list of inbound OPMs that
 *       should be treated as trusted.
 * </ul>
 *
 * <p>Enforcement never runs when the local OPM is unknown (the agent has not yet reported one).
 */
final class OrgGuardEnforcer {

  private static final Logger log = LoggerFactory.getLogger(OrgGuardEnforcer.class);

  private final boolean strict;
  private final Set<String> trustedOpms;
  private final Supplier<String> localOpmSupplier;
  private final PropagationTags.Factory factory;
  private final HealthMetrics healthMetrics;

  OrgGuardEnforcer(
      Config config,
      Supplier<String> localOpmSupplier,
      PropagationTags.Factory factory,
      HealthMetrics healthMetrics) {
    this(
        config.isTraceOrgGuardStrict(),
        config.getTraceOrgGuardTrustedOpms(),
        localOpmSupplier,
        factory,
        healthMetrics);
  }

  // Visible for testing.
  OrgGuardEnforcer(
      boolean strict,
      Set<String> trustedOpms,
      Supplier<String> localOpmSupplier,
      PropagationTags.Factory factory,
      HealthMetrics healthMetrics) {
    this.strict = strict;
    this.trustedOpms = trustedOpms;
    this.localOpmSupplier = localOpmSupplier;
    this.factory = factory;
    this.healthMetrics = healthMetrics;
  }

  Supplier<String> localOpmSupplier() {
    return localOpmSupplier;
  }

  /**
   * Returns {@code extracted} unchanged unless OPG enforcement applies, in which case it returns a
   * fresh {@link ExtractedContext} with the Datadog-side context dropped.
   */
  public TagContext enforce(TagContext extracted) {
    if (!(extracted instanceof ExtractedContext)) {
      return extracted;
    }
    ExtractedContext ctx = (ExtractedContext) extracted;
    String localOpm = localOpmSupplier.get();
    if (localOpm == null) {
      // We don't know our own OPM yet — never enforce.
      return extracted;
    }
    CharSequence opmChars = ctx.getPropagationTags().getOrgPropagationMarker();
    if (opmChars == null) {
      if (!strict) {
        return extracted;
      }
      return strip(ctx, OrgGuard.Reason.STRICT_MISSING, localOpm, null);
    }
    String inboundOpm = opmChars.toString();
    if (localOpm.equals(inboundOpm) || trustedOpms.contains(inboundOpm)) {
      return extracted;
    }
    return strip(ctx, OrgGuard.Reason.MISMATCH, localOpm, inboundOpm);
  }

  private ExtractedContext strip(
      ExtractedContext ctx, OrgGuard.Reason reason, String localOpm, String inboundOpm) {
    log.debug(
        "OPG enforcement: dropping dd context (reason={}, inbound={}, local={})",
        reason.tag(),
        inboundOpm,
        localOpm);
    healthMetrics.onOrgGuardEnforce(reason);

    PropagationTags stripped = factory.emptyW3C(ctx.getPropagationTags().getW3CTracestate());
    return new ExtractedContext(
        ctx.getTraceId(),
        ctx.getSpanId(),
        PrioritySampling.UNSET,
        null,
        ctx.getEndToEndStartTime(),
        ctx.getBaggage(),
        ctx.getTags(),
        null,
        stripped,
        ctx.getTraceConfig(),
        ctx.getPropagationStyle());
  }
}
