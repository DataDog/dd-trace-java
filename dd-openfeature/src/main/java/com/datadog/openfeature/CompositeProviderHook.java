package com.datadog.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The provider's hooks, registered with OpenFeature as one hook.
 *
 * <p>On every evaluation, the OpenFeature SDK builds a list entry, a hook context and hook data for
 * each registered hook, then calls each hook at every stage. All of the provider's hooks only act
 * at the finally stage, so running them behind one hook pays that setup once, whatever the number
 * of hooks.
 *
 * <p>It keeps what the SDK does for separate hooks: the hooks run in registration order, a hook is
 * skipped for a flag type it does not support, and a hook that throws is logged without stopping
 * the others. The hooks share this hook's {@link HookContext}, including its hook data, so any hook
 * data keys they use must be distinct.
 *
 * <p>It also gates the evaluation telemetry hooks on the {@code dd_evaluation_telemetry_opt_out}
 * context parameter, deciding once per evaluation for all of them.
 */
final class CompositeProviderHook implements Hook<Object> {
  private static final Logger log = LoggerFactory.getLogger(CompositeProviderHook.class);

  static final String OPT_OUT_PARAMETER = "dd_evaluation_telemetry_opt_out";
  private static final String OPT_OUT_DECISION = "datadog.evaluationTelemetryOptOut";

  private final Hook<Object>[] hooks;

  /** Parallel to {@link #hooks}: whether the evaluation telemetry opt-out skips the hook. */
  private final boolean[] telemetry;

  private final boolean optOutEnabled;

  private CompositeProviderHook(
      final Hook<Object>[] hooks, final boolean[] telemetry, final boolean optOutEnabled) {
    this.hooks = hooks;
    this.telemetry = telemetry;
    this.optOutEnabled = optOutEnabled;
  }

  /** Collects the provider's hooks in the order they run. */
  static final class Builder {
    private final List<Hook<?>> hooks = new ArrayList<>(5);
    private final List<Boolean> telemetry = new ArrayList<>(5);
    private boolean optOutEnabled;

    /** Adds an evaluation telemetry hook, which the opt-out skips when it is enabled. */
    Builder addTelemetry(final Hook<?> hook) {
      return add(hook, true);
    }

    /** Adds a hook that always runs, such as exposures or local debug diagnostics. */
    Builder add(final Hook<?> hook) {
      return add(hook, false);
    }

    /** Honors the {@code dd_evaluation_telemetry_opt_out} parameter for telemetry hooks. */
    Builder optOutEnabled(final boolean optOutEnabled) {
      this.optOutEnabled = optOutEnabled;
      return this;
    }

    private Builder add(final Hook<?> hook, final boolean isTelemetry) {
      this.hooks.add(hook);
      this.telemetry.add(isTelemetry);
      return this;
    }

    /** Returns the hooks to register: none, or the one composite hook. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    List<Hook> build() {
      if (this.hooks.isEmpty()) {
        return Collections.emptyList();
      }
      final boolean[] telemetry = new boolean[this.telemetry.size()];
      for (int i = 0; i < telemetry.length; i++) {
        telemetry[i] = this.telemetry.get(i);
      }
      return Collections.<Hook>singletonList(
          new CompositeProviderHook(
              this.hooks.toArray(new Hook[0]), telemetry, this.optOutEnabled));
    }
  }

  /** The hooks behind this one, in the order they run. */
  List<Hook<Object>> hooks() {
    final List<Hook<Object>> list = new ArrayList<>(this.hooks.length);
    Collections.addAll(list, this.hooks);
    return Collections.unmodifiableList(list);
  }

  @Override
  public Optional<EvaluationContext> before(
      final HookContext<Object> context, final Map<String, Object> hints) {
    if (this.optOutEnabled) {
      // Provider before hooks see the merged context, including application before hooks.
      // Hook data belongs to this evaluation, including nested and concurrent calls.
      context.getHookData().set(OPT_OUT_DECISION, isOptedOut(context.getCtx()));
    }
    return Optional.empty();
  }

  @Override
  public void error(
      final HookContext<Object> context, final Exception error, final Map<String, Object> hints) {
    // A failing application before hook can prevent our before hook from running. Preserve its
    // decision before application error hooks can change the context.
    if (this.optOutEnabled && context.getHookData().get(OPT_OUT_DECISION) == null) {
      context.getHookData().set(OPT_OUT_DECISION, isOptedOut(context.getCtx()));
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    final boolean optedOut =
        this.optOutEnabled
            && (Boolean.TRUE.equals(context.getHookData().get(OPT_OUT_DECISION))
                || isOptedOut(context.getCtx()));
    final Hook<Object>[] hooks = this.hooks;
    for (int i = 0; i < hooks.length; i++) {
      final Hook<Object> hook = hooks[i];
      if ((optedOut && this.telemetry[i]) || !hook.supportsFlagValueType(context.getType())) {
        continue;
      }
      try {
        hook.finallyAfter(context, details, hints);
      } catch (final Exception e) {
        // As the SDK does for a failing finally hook: log it and run the rest.
        log.error("Unhandled exception when running finallyAfter hook {}", hook.getClass(), e);
      }
    }
  }

  static boolean isOptedOut(final EvaluationContext context) {
    final Value value = context == null ? null : context.getValue(OPT_OUT_PARAMETER);
    return value != null && value.isBoolean() && Boolean.TRUE.equals(value.asBoolean());
  }

  @Override
  public String toString() {
    final StringBuilder names = new StringBuilder("CompositeProviderHook[");
    for (int i = 0; i < this.hooks.length; i++) {
      names.append(i == 0 ? "" : ", ").append(this.hooks[i].getClass().getSimpleName());
    }
    return names.append(']').toString();
  }
}
