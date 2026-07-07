package datadog.trace.api.openfeature;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Map;

/**
 * OpenFeature {@code finally} hook that captures feature-flag evaluation metadata into
 * per-local-root span state for APM span enrichment. This is the CAPTURE half of the
 * capture-vs-write split — the WRITE half is {@link SpanEnrichmentInterceptor}, which flushes the
 * accumulated tags onto the local root span when the trace completes.
 *
 * <p>Mirrors {@link FlagEvalHook}: registered via {@link Provider#getProviderHooks()} (only when
 * the span-enrichment gate is on) and reading {@code details.getFlagMetadata()}. It resolves the
 * active local-root span via {@link AgentTracer#activeSpan()} and keys the {@link
 * SpanEnrichmentStates} store (shared with the {@link SpanEnrichmentInterceptor}) by that root span
 * object, so the interceptor running later on the write thread can recover the same state from the
 * completed span collection (the local root is a single stable object for the trace).
 *
 * <p>Capture branch (frozen Node reference):
 *
 * <ul>
 *   <li>serial id present → addSerialId, plus addSubject when {@code __dd_do_log} AND a targeting
 *       key
 *   <li>else variant missing (runtime default) → addDefault(flagKey, value)
 * </ul>
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break flag evaluation.
 */
class SpanEnrichmentHook implements Hook<Object> {

  static final String METADATA_SERIAL_ID = "__dd_split_serial_id";
  static final String METADATA_DO_LOG = "__dd_do_log";

  /**
   * Resolves the local-root span for the active trace. Injectable so tests need no static mocks.
   */
  interface RootSpanResolver {
    AgentSpan activeLocalRoot();
  }

  private static final RootSpanResolver DEFAULT_RESOLVER =
      () -> {
        final AgentSpan active = AgentTracer.activeSpan();
        if (active == null) {
          return null;
        }
        final AgentSpan localRoot = active.getLocalRootSpan();
        return localRoot != null ? localRoot : active;
      };

  private final RootSpanResolver rootSpanResolver;
  // State store shared with the interceptor. The hook writes here; the interceptor reads + removes.
  private final SpanEnrichmentStates states;

  SpanEnrichmentHook(final SpanEnrichmentStates states) {
    this(DEFAULT_RESOLVER, states);
  }

  SpanEnrichmentHook(final RootSpanResolver rootSpanResolver, final SpanEnrichmentStates states) {
    this.rootSpanResolver = rootSpanResolver;
    this.states = states;
  }

  @Override
  public void finallyAfter(
      final HookContext<Object> ctx,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    if (details == null) {
      return;
    }
    try {
      final AgentSpan root = rootSpanResolver.activeLocalRoot();
      if (root == null) {
        return; // no active span → nothing to enrich
      }
      capture(root, ctx, details);
    } catch (final Throwable t) {
      // Never let span enrichment break flag evaluation.
    }
  }

  /**
   * Applies the frozen Node capture branch against the state keyed by the local-root {@code root}
   * span. Package private so it can be driven deterministically in tests without stubbing the
   * static tracer.
   */
  void capture(
      final AgentSpan root,
      final HookContext<Object> ctx,
      final FlagEvaluationDetails<Object> details) {
    final ImmutableMetadata metadata = details.getFlagMetadata();
    final String serialIdStr = metadata != null ? metadata.getString(METADATA_SERIAL_ID) : null;
    final String doLogStr = metadata != null ? metadata.getString(METADATA_DO_LOG) : null;
    final boolean doLog = "true".equalsIgnoreCase(doLogStr);
    final String targetingKey = targetingKey(ctx);

    if (serialIdStr != null) {
      final int serialId;
      try {
        serialId = Integer.parseInt(serialIdStr);
      } catch (final NumberFormatException e) {
        return; // malformed serial id — drop, never break eval
      }
      final SpanEnrichmentAccumulator state = states.getOrCreate(root);
      state.addSerialId(serialId);
      if (doLog && targetingKey != null) {
        state.addSubject(targetingKey, serialId);
      }
    } else if (details.getVariant() == null) {
      // Runtime-default detection = MISSING VARIANT (never a reason enum).
      states.getOrCreate(root).addDefault(details.getFlagKey(), details.getValue());
    }
  }

  private static String targetingKey(final HookContext<Object> ctx) {
    if (ctx == null) {
      return null;
    }
    final EvaluationContext evaluationContext = ctx.getCtx();
    if (evaluationContext == null) {
      return null;
    }
    final String key = evaluationContext.getTargetingKey();
    return (key == null || key.isEmpty()) ? null : key;
  }
}
