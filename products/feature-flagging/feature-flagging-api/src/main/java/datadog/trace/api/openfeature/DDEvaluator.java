package datadog.trace.api.openfeature;

import datadog.openfeature.internal.core.ConfigurationSnapshot;
import datadog.openfeature.internal.core.EvaluationResult;
import datadog.openfeature.internal.core.EvaluationResult.Reason;
import datadog.openfeature.internal.core.FlagEvaluator;
import datadog.openfeature.internal.core.FlagEvaluator.ValueKind;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Thin OpenFeature adapter over the provider-owned core runtime. */
class DDEvaluator implements Evaluator {

  static final String METADATA_SPLIT_SERIAL_ID = "__dd_split_serial_id";
  static final String METADATA_DO_LOG = "__dd_do_log";

  private static final boolean SPAN_ENRICHMENT_ENABLED = SpanEnrichmentGate.isEnabled();

  private final Runnable configCallback;
  private final Provider.Options options;
  private final FlagEvaluator evaluator = new FlagEvaluator();
  private volatile ProviderRuntime.Handle runtime;

  DDEvaluator(final Runnable configCallback, final Provider.Options options) {
    this.configCallback = configCallback;
    this.options = options;
  }

  @Override
  public boolean initialize(
      final long timeout, final TimeUnit unit, final EvaluationContext context) throws Exception {
    ProviderRuntime.Handle current = runtime;
    if (current == null) {
      synchronized (this) {
        current = runtime;
        if (current == null) {
          current =
              ProviderRuntime.acquire(
                  RuntimeConfiguration.resolve(options), ignored -> configCallback.run());
          runtime = current;
        }
      }
    }
    return current.awaitConfiguration(timeout, unit) || hasConfiguration();
  }

  @Override
  public boolean hasConfiguration() {
    final ProviderRuntime.Handle current = runtime;
    return current != null && current.configuration() != null;
  }

  @Override
  public void shutdown() {
    final ProviderRuntime.Handle current = runtime;
    runtime = null;
    if (current != null) {
      current.close();
    }
  }

  @Override
  public <T> ProviderEvaluation<T> evaluate(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    final ProviderRuntime.Handle current = runtime;
    final ConfigurationSnapshot snapshot = current == null ? null : current.configuration();
    final EvaluationResult result =
        evaluator.evaluate(
            snapshot,
            valueKind(target),
            key,
            unwrapDefaultValue(defaultValue),
            toCoreContext(context));
    return toProviderEvaluation(target, key, defaultValue, context, result);
  }

  private static ValueKind valueKind(final Class<?> target) {
    if (target == Boolean.class) {
      return ValueKind.BOOLEAN;
    }
    if (target == String.class) {
      return ValueKind.STRING;
    }
    if (target == Integer.class) {
      return ValueKind.INTEGER;
    }
    if (target == Double.class) {
      return ValueKind.DOUBLE;
    }
    if (target == Value.class) {
      return ValueKind.OBJECT;
    }
    throw new IllegalArgumentException("Type not supported: " + target);
  }

  private static datadog.openfeature.internal.core.EvaluationContext toCoreContext(
      final EvaluationContext context) {
    if (context == null) {
      return null;
    }
    final Map<String, Object> attributes = new LinkedHashMap<>();
    for (final String key : context.keySet()) {
      attributes.put(key, unwrapValue(context.getValue(key)));
    }
    return new datadog.openfeature.internal.core.EvaluationContext(
        context.getTargetingKey(), attributes);
  }

  private static <T> ProviderEvaluation<T> toProviderEvaluation(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context,
      final EvaluationResult result) {
    if (result.error != null) {
      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(dev.openfeature.sdk.Reason.ERROR.name())
          .errorCode(errorCode(result.error))
          .errorMessage(result.errorMessage)
          .build();
    }

    final ImmutableMetadata.ImmutableMetadataBuilder metadata = ImmutableMetadata.builder();
    if (result.flagKey != null) {
      metadata.addString("flagKey", result.flagKey);
    }
    if (result.variationType != null) {
      metadata.addString("variationType", result.variationType);
    }
    if (result.allocationKey != null) {
      metadata.addString("allocationKey", result.allocationKey);
    }
    if (SPAN_ENRICHMENT_ENABLED) {
      if (result.splitSerialId != null) {
        metadata.addInteger(METADATA_SPLIT_SERIAL_ID, result.splitSerialId);
      }
      metadata.addBoolean(METADATA_DO_LOG, result.doLog);
    }

    final T value =
        target == Value.class
                && (result.reason == Reason.DISABLED || result.reason == Reason.DEFAULT)
            ? defaultValue
            : mapResultValue(target, result.value);
    final ProviderEvaluation<T> evaluation =
        ProviderEvaluation.<T>builder()
            .value(value)
            .reason(result.reason.name())
            .variant(result.variant)
            .flagMetadata(metadata.build())
            .build();
    if (result.doLog && context != null && result.allocationKey != null && result.variant != null) {
      RawBridgeAccess.dispatchExposure(
          System.currentTimeMillis(),
          result.allocationKey,
          key,
          result.variant,
          context.getTargetingKey(),
          flattenContext(context));
    }
    return evaluation;
  }

  private static ErrorCode errorCode(final EvaluationResult.Error error) {
    switch (error) {
      case PROVIDER_NOT_READY:
        return ErrorCode.PROVIDER_NOT_READY;
      case INVALID_CONTEXT:
        return ErrorCode.INVALID_CONTEXT;
      case FLAG_NOT_FOUND:
        return ErrorCode.FLAG_NOT_FOUND;
      case TARGETING_KEY_MISSING:
        return ErrorCode.TARGETING_KEY_MISSING;
      case TYPE_MISMATCH:
        return ErrorCode.TYPE_MISMATCH;
      case PARSE_ERROR:
        return ErrorCode.PARSE_ERROR;
      default:
        return ErrorCode.GENERAL;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T mapResultValue(final Class<T> target, final Object value) {
    if (target == Value.class) {
      return (T) Value.objectToValue(value);
    }
    return target.cast(value);
  }

  @SuppressWarnings("unchecked")
  static <T> T mapValue(final Class<T> target, final Object value) {
    final Object mapped = FlagEvaluator.mapValue(valueKind(target), value);
    return target == Value.class ? (T) Value.objectToValue(mapped) : target.cast(mapped);
  }

  static AbstractMap<String, Object> flattenContext(final EvaluationContext context) {
    final Set<String> keys = context.keySet();
    final HashMap<String, Object> result = new HashMap<>();
    final Set<Value> seen = new HashSet<>();
    for (final String key : keys) {
      final Deque<FlattenEntry> deque = new LinkedList<>();
      deque.push(new FlattenEntry(key, context.getValue(key)));
      while (!deque.isEmpty()) {
        final FlattenEntry entry = deque.pop();
        final Value value = entry.value;
        if (value == null || seen.add(value)) {
          if (value == null) {
            result.put(entry.key, null);
          } else if (value.isList()) {
            final List<Value> list = value.asList();
            for (int i = 0; i < list.size(); i++) {
              deque.push(new FlattenEntry(entry.key + "[" + i + "]", list.get(i)));
            }
          } else if (value.isStructure()) {
            final Structure structure = value.asStructure();
            for (final String property : structure.keySet()) {
              deque.push(
                  new FlattenEntry(entry.key + "." + property, structure.getValue(property)));
            }
          } else {
            result.put(entry.key, context.convertValue(value));
          }
        }
      }
    }
    return result;
  }

  static Object unwrapDefaultValue(final Object value) {
    return value instanceof Value ? unwrapValue((Value) value) : value;
  }

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
      final List<Object> output = new ArrayList<>(list == null ? 0 : list.size());
      if (list != null) {
        for (final Value element : list) {
          output.add(unwrapValue(element));
        }
      }
      return output;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isString()) {
      return value.asString();
    }
    if (value.isNumber()) {
      final Double number = value.asDouble();
      if (number != null && number == Math.rint(number) && !Double.isInfinite(number)) {
        final Integer integer = value.asInteger();
        if (integer != null) {
          return integer;
        }
      }
      return number;
    }
    final Instant instant = value.asInstant();
    return instant == null ? value.asObject() : instant.toString();
  }

  private static final class FlattenEntry {
    private final String key;
    private final Value value;

    private FlattenEntry(final String key, final Value value) {
      this.key = key;
      this.value = value;
    }
  }
}
