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
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps OpenFeature values to the shared evaluator model. */
final class OpenFeatureEvaluationAdapter {

  interface ExposureHandler {
    void accept(
        String flagKey,
        String allocationKey,
        String variantKey,
        EvaluationContext evaluationContext);
  }

  private static final ExposureHandler NO_EXPOSURES =
      (flagKey, allocationKey, variantKey, evaluationContext) -> {};

  private final FlagEvaluator evaluator = new FlagEvaluator();
  private final ExposureHandler exposureHandler;
  private final boolean spanEnrichmentEnabled;

  OpenFeatureEvaluationAdapter(
      final ExposureHandler exposureHandler, final boolean spanEnrichmentEnabled) {
    this.exposureHandler = exposureHandler == null ? NO_EXPOSURES : exposureHandler;
    this.spanEnrichmentEnabled = spanEnrichmentEnabled;
  }

  <T> ProviderEvaluation<T> evaluate(
      final ConfigurationSnapshot snapshot,
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    final EvaluationResult result =
        evaluator.evaluate(
            snapshot,
            valueKind(target),
            key,
            unwrapDefaultValue(defaultValue),
            toCoreContext(context));
    return toProviderEvaluation(target, key, defaultValue, context, result);
  }

  private <T> ProviderEvaluation<T> toProviderEvaluation(
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
    if (spanEnrichmentEnabled) {
      if (result.splitSerialId != null) {
        metadata.addInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID, result.splitSerialId);
      }
      metadata.addBoolean(DDEvaluator.METADATA_DO_LOG, result.doLog);
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
      exposureHandler.accept(key, result.allocationKey, result.variant, context);
    }
    return evaluation;
  }

  static ValueKind valueKind(final Class<?> target) {
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
    return datadog.openfeature.internal.core.EvaluationContext.lazy(
        context.getTargetingKey(),
        new datadog.openfeature.internal.core.EvaluationContext.AttributeProvider() {
          @Override
          public boolean contains(final String name) {
            return context.keySet().contains(name);
          }

          @Override
          public Object get(final String name) {
            return unwrapValue(context.getValue(name));
          }
        });
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
    return mapped == null
        ? null
        : target == Value.class ? (T) Value.objectToValue(mapped) : target.cast(mapped);
  }

  static Object unwrapDefaultValue(final Object value) {
    return value instanceof Value ? unwrapValue((Value) value) : value;
  }

  private static Object unwrapValue(final Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isStructure()) {
      final Map<String, Object> map = new LinkedHashMap<>();
      if (value.asStructure() != null) {
        for (final String key : value.asStructure().keySet()) {
          map.put(key, unwrapValue(value.asStructure().getValue(key)));
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
}
