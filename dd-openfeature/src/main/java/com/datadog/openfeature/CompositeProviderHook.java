package com.datadog.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider's hooks as one OpenFeature hook. The SDK sets up a hook context, hook data and a
 * list entry for every hook on every evaluation, so running the provider's finally-stage hooks
 * behind one hook pays that setup once.
 *
 * <p>It keeps what the SDK does for separate hooks: the hooks run in registration order, and one
 * that throws is logged without stopping the others. The evaluation telemetry opt-out is decided
 * once per evaluation, in place of one {@link EvaluationTelemetryOptOutHook} per gated hook.
 */
final class CompositeProviderHook implements Hook<Object> {
  private static final Logger log = LoggerFactory.getLogger(CompositeProviderHook.class);
  private static final String DECISION = "datadog.evaluationTelemetryOptOut";

  private final Hook<Object>[] hooks;

  /** Parallel to {@link #hooks}: whether the evaluation telemetry opt-out skips the hook. */
  private final boolean[] gated;

  private final boolean optOutEnabled;

  @SuppressWarnings("unchecked")
  CompositeProviderHook(
      final List<? extends Hook<?>> hooks, final List<Boolean> gated, final boolean optOutEnabled) {
    this.hooks = hooks.toArray(new Hook[0]);
    this.gated = new boolean[gated.size()];
    for (int i = 0; i < this.gated.length; i++) {
      this.gated[i] = gated.get(i);
    }
    this.optOutEnabled = optOutEnabled;
  }

  @Override
  public Optional<EvaluationContext> before(
      final HookContext<Object> context, final Map<String, Object> hints) {
    if (this.optOutEnabled) {
      // Provider before hooks see the merged context, including application before hooks.
      context
          .getHookData()
          .set(DECISION, EvaluationTelemetryOptOutHook.isOptedOut(context.getCtx()));
    }
    return Optional.empty();
  }

  @Override
  public void error(
      final HookContext<Object> context, final Exception error, final Map<String, Object> hints) {
    // A failing application before hook can prevent our before hook from running. Preserve its
    // decision before application error hooks can change the context.
    if (this.optOutEnabled && context.getHookData().get(DECISION) == null) {
      context
          .getHookData()
          .set(DECISION, EvaluationTelemetryOptOutHook.isOptedOut(context.getCtx()));
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    final boolean optedOut =
        this.optOutEnabled
            && (Boolean.TRUE.equals(context.getHookData().get(DECISION))
                || EvaluationTelemetryOptOutHook.isOptedOut(context.getCtx()));
    final Hook<Object>[] hooks = this.hooks;
    for (int i = 0; i < hooks.length; i++) {
      if (optedOut && this.gated[i]) {
        continue;
      }
      try {
        hooks[i].finallyAfter(context, details, hints);
      } catch (final Exception e) {
        // The SDK logs a failing finally hook and runs the rest.
        log.error("Unhandled exception when running finallyAfter hook {}", hooks[i].getClass(), e);
      }
    }
  }
}
