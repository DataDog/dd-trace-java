package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.SpanEnrichmentEvent;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenFeature {@code finally} hook that captures feature-flag evaluation metadata for APM span
 * enrichment. This is the CAPTURE half of the capture-vs-write split, and it runs in the
 * application classloader (returned from {@link Provider#getProviderHooks()} only when the gate is
 * on).
 *
 * <p>It holds <b>no tracer dependency</b>: rather than resolving the active span itself, it emits a
 * {@link SpanEnrichmentEvent} onto {@link FeatureFlaggingGateway}. The agent-side write tier
 * ({@code SpanEnrichmentWriter} in {@code feature-flagging-lib}) receives the event, resolves the
 * active local-root span, and accumulates the per-trace state that is flushed onto the root when
 * the trace completes. This keeps the published {@code dd-openfeature} product API free of {@code
 * internal-api}.
 *
 * <p>Capture branch (frozen Node reference):
 *
 * <ul>
 *   <li>serial id present → dispatch a serial-id event (the write side records the serial id, plus
 *       the subject when {@code __dd_do_log} AND a targeting key)
 *   <li>else variant missing (runtime default) → dispatch a runtime-default event with the value
 *       unwrapped to a native Java type
 * </ul>
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break flag evaluation.
 */
class SpanEnrichmentHook implements Hook<Object> {

  // The metadata keys the DDEvaluator attaches for span enrichment (single source of truth there).
  static final String METADATA_SERIAL_ID = DDEvaluator.METADATA_SPLIT_SERIAL_ID;
  static final String METADATA_DO_LOG = DDEvaluator.METADATA_DO_LOG;

  @Override
  public void finallyAfter(
      final HookContext<Object> ctx,
      final FlagEvaluationDetails<Object> details,
      final Map<String, Object> hints) {
    if (details == null) {
      return;
    }
    try {
      final ImmutableMetadata metadata = details.getFlagMetadata();
      final String serialIdStr = metadata != null ? metadata.getString(METADATA_SERIAL_ID) : null;
      if (serialIdStr != null) {
        final int serialId;
        try {
          serialId = Integer.parseInt(serialIdStr);
        } catch (final NumberFormatException e) {
          return; // malformed serial id — drop, never break eval
        }
        final String doLogStr = metadata.getString(METADATA_DO_LOG);
        final boolean doLog = "true".equalsIgnoreCase(doLogStr);
        FeatureFlaggingGateway.dispatch(
            SpanEnrichmentEvent.serialId(serialId, doLog, targetingKey(ctx)));
      } else if (details.getVariant() == null) {
        // Runtime-default detection = MISSING VARIANT (never a reason enum). Unwrap any OpenFeature
        // Value to a native Java type here so the seam carries only JDK types.
        FeatureFlaggingGateway.dispatch(
            SpanEnrichmentEvent.runtimeDefault(
                details.getFlagKey(), unwrapDefaultValue(details.getValue())));
      }
    } catch (final Throwable t) {
      // Never let span enrichment break flag evaluation.
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

  /**
   * Unwraps an OpenFeature {@link Value} to a native Java representation so the runtime default can
   * cross the (JDK-types-only) seam and be JSON-serialized on the write side exactly like Node's
   * {@code JSON.stringify}. Non-{@code Value} inputs (already native) are returned as-is.
   */
  static Object unwrapDefaultValue(final Object value) {
    return value instanceof Value ? unwrapValue((Value) value) : value;
  }

  /**
   * Recursively unwraps an OpenFeature {@link Value} into its native Java representation:
   * structures become {@code Map<String, Object>}, lists become {@code List<Object>}, and scalars
   * become their boxed value (or {@code null}). Nested {@link Value}s are unwrapped at every level
   * so a structure containing further structures/lists serializes correctly.
   */
  private static Object unwrapValue(final Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isStructure()) {
      final Structure structure = value.asStructure();
      final Map<String, Object> map = new LinkedHashMap<>();
      if (structure != null) {
        for (final String key : structure.keySet()) {
          map.put(key, unwrapValue(structure.getValue(key)));
        }
      }
      return map;
    }
    if (value.isList()) {
      final List<Value> list = value.asList();
      final List<Object> out = new ArrayList<>(list == null ? 0 : list.size());
      if (list != null) {
        for (final Value element : list) {
          out.add(unwrapValue(element));
        }
      }
      return out;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isString()) {
      return value.asString();
    }
    if (value.isNumber()) {
      // Preserve integral vs fractional so the rendered JSON number matches Node.
      final Double d = value.asDouble();
      if (d != null && d == Math.rint(d) && !Double.isInfinite(d)) {
        final Integer i = value.asInteger();
        if (i != null) {
          return i;
        }
      }
      return d;
    }
    final Instant instant = value.asInstant();
    if (instant != null) {
      return instant.toString();
    }
    // Unknown shape: fall back to the wrapped object's own representation.
    return value.asObject();
  }
}
