package datadog.trace.api.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.Value;
import java.util.Map;
import java.util.Optional;

/** Gates a Datadog finally-only evaluation telemetry hook, not exposure or application hooks. */
final class EvaluationTelemetryOptOutHook implements Hook<Object> {
  private static final String PARAMETER = "dd_evaluation_telemetry_opt_out";
  private static final String DECISION = "datadog.evaluationTelemetryOptOut";
  private final Hook<Object> delegate;

  EvaluationTelemetryOptOutHook(final Hook<Object> delegate) {
    this.delegate = delegate;
  }

  @Override
  public Optional<EvaluationContext> before(
      final HookContext<Object> context, final Map<String, Object> hints) {
    // Provider before hooks see the merged context, including application before hooks.
    // HookData belongs to this evaluation, including nested and concurrent calls.
    context.getHookData().set(DECISION, isOptedOut(context.getCtx()));
    return Optional.empty();
  }

  @Override
  public void error(
      final HookContext<Object> context, final Exception error, final Map<String, Object> hints) {
    // A failing application before hook can prevent our before hook from running.
    // Preserve its decision before application error hooks can change the context.
    if (context.getHookData().get(DECISION) == null) {
      context.getHookData().set(DECISION, isOptedOut(context.getCtx()));
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    if (Boolean.TRUE.equals(context.getHookData().get(DECISION)) || isOptedOut(context.getCtx())) {
      return;
    }
    delegate.finallyAfter(context, details, hints);
  }

  private static boolean isOptedOut(final EvaluationContext context) {
    final Value value = context == null ? null : context.getValue(PARAMETER);
    return value != null && value.isBoolean() && Boolean.TRUE.equals(value.asBoolean());
  }
}
