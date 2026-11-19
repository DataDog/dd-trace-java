package com.datadog.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.Value;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

/** Gates a Datadog finally-only evaluation telemetry hook, not exposure or application hooks. */
final class EvaluationTelemetryOptOutHook implements Hook<Object> {
  private static final String PARAMETER = "dd_evaluation_telemetry_opt_out";
  private static final String DECISION = "datadog.evaluationTelemetryOptOut";
  private final Hook<Object> delegate;
  private final ThreadLocal<Deque<LegacyDecision>> legacyDecisions = new ThreadLocal<>();

  EvaluationTelemetryOptOutHook(final Hook<Object> delegate) {
    this.delegate = delegate;
  }

  @Override
  public Optional<EvaluationContext> before(
      final HookContext<Object> context, final Map<String, Object> hints) {
    // Provider before hooks see the merged context, including application before hooks.
    // HookData belongs to this evaluation, including nested and concurrent calls.
    final boolean optedOut = isOptedOut(context.getCtx());
    if (!HookDataAccessor.set(context, DECISION, optedOut)) {
      Deque<LegacyDecision> decisions = this.legacyDecisions.get();
      if (decisions == null) {
        decisions = new ArrayDeque<>();
        this.legacyDecisions.set(decisions);
      }
      decisions.push(new LegacyDecision(hints, optedOut));
    }
    return delegate.before(context, hints);
  }

  @Override
  public void error(
      final HookContext<Object> context, final Exception error, final Map<String, Object> hints) {
    // A failing application before hook can prevent our before hook from running.
    // Preserve its decision before application error hooks can change the context.
    if (HookDataAccessor.get(context, DECISION) == null) {
      if (!HookDataAccessor.set(context, DECISION, isOptedOut(context.getCtx()))) {
        Deque<LegacyDecision> decisions = this.legacyDecisions.get();
        if (decisions == null) {
          decisions = new ArrayDeque<>();
          this.legacyDecisions.set(decisions);
        }
        if (decisions.isEmpty() || decisions.peek().invocation != hints) {
          decisions.push(new LegacyDecision(hints, true));
        }
      }
    }
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> context,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    Object decision = HookDataAccessor.take(context, DECISION);
    final Deque<LegacyDecision> decisions = this.legacyDecisions.get();
    if (decision == null
        && decisions != null
        && !decisions.isEmpty()
        && decisions.peek().invocation == hints) {
      decision = decisions.pop().optedOut;
      if (decisions.isEmpty()) {
        this.legacyDecisions.remove();
      }
    }
    // If neither HookData nor the legacy invocation stack owns this call, provider before did not
    // run (or storage failed). Suppress telemetry rather than consuming a nested/outer decision.
    if (decision == null) {
      return;
    }
    if (Boolean.TRUE.equals(decision) || isOptedOut(context.getCtx())) {
      return;
    }
    delegate.finallyAfter(context, details, hints);
  }

  private static boolean isOptedOut(final EvaluationContext context) {
    final Value value = context == null ? null : context.getValue(PARAMETER);
    return value != null && value.isBoolean() && Boolean.TRUE.equals(value.asBoolean());
  }

  private static final class LegacyDecision {
    private final Object invocation;
    private final boolean optedOut;

    private LegacyDecision(final Object invocation, final boolean optedOut) {
      this.invocation = invocation;
      this.optedOut = optedOut;
    }
  }
}
