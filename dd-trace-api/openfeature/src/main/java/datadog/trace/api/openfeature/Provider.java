package datadog.trace.api.openfeature;

import static datadog.trace.api.featureflag.FeatureFlag.EVALUATOR;

import datadog.trace.api.featureflag.FeatureFlagEvaluator;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Provider extends EventProvider implements Metadata, FeatureFlagEvaluator.Listener {

  private static final Logger LOGGER = LoggerFactory.getLogger(Provider.class);
  private static final String METADATA = "datadog-openfeature-provider";

  public Provider() {
    EVALUATOR.addListener(this);
  }

  @Override
  public void onInitialized() {
    emit(
        ProviderEvent.PROVIDER_READY,
        ProviderEventDetails.builder().message("Provider ready").build());
  }

  @Override
  public void onConfigurationChanged() {
    emit(
        ProviderEvent.PROVIDER_CONFIGURATION_CHANGED,
        ProviderEventDetails.builder().message("New configuration received").build());
  }

  @Override
  public Metadata getMetadata() {
    return this;
  }

  @Override
  public String getName() {
    return METADATA;
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      final String key, final Boolean defaultValue, final EvaluationContext ctx) {
    try {
      return map(Boolean.class, EVALUATOR.evaluate(key, defaultValue, new ContextAdapter(ctx)));
    } catch (final FeatureFlagEvaluator.EvaluationError e) {
      return fromError(e, defaultValue);
    }
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      final String key, final String defaultValue, final EvaluationContext ctx) {
    try {
      return map(String.class, EVALUATOR.evaluate(key, defaultValue, new ContextAdapter(ctx)));
    } catch (final FeatureFlagEvaluator.EvaluationError e) {
      return fromError(e, defaultValue);
    }
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      final String key, final Integer defaultValue, final EvaluationContext ctx) {
    try {
      return map(Integer.class, EVALUATOR.evaluate(key, defaultValue, new ContextAdapter(ctx)));
    } catch (final FeatureFlagEvaluator.EvaluationError e) {
      return fromError(e, defaultValue);
    }
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      final String key, final Double defaultValue, final EvaluationContext ctx) {
    try {
      return map(Double.class, EVALUATOR.evaluate(key, defaultValue, new ContextAdapter(ctx)));
    } catch (final FeatureFlagEvaluator.EvaluationError e) {
      return fromError(e, defaultValue);
    }
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      final String key, final Value defaultValue, final EvaluationContext ctx) {
    try {
      return map(Value.class, EVALUATOR.evaluate(key, defaultValue, new ContextAdapter(ctx)));
    } catch (final FeatureFlagEvaluator.EvaluationError e) {
      return fromError(e, defaultValue);
    }
  }

  @SuppressWarnings("unchecked")
  private <V, R> ProviderEvaluation<V> map(
      final Class<V> evalClass, final FeatureFlagEvaluator.Resolution<R> result) {
    Object value = result.getValue();
    if (evalClass == Value.class) {
      value = value instanceof Value ? value : Value.objectToValue(value);
    }
    return (ProviderEvaluation<V>)
        ProviderEvaluation.builder()
            .value(value)
            .reason(result.getReason())
            .variant(result.getVariant())
            .flagMetadata(mapFlagMetadata(result.getFlagMetadata()))
            .build();
  }

  private ImmutableMetadata mapFlagMetadata(final Map<String, Object> metadata) {
    final ImmutableMetadata.ImmutableMetadataBuilder builder = ImmutableMetadata.builder();
    metadata.forEach(
        (key, value) -> {
          if (value instanceof Integer) {
            builder.addInteger(key, (Integer) value);
          } else if (value instanceof Float) {
            builder.addFloat(key, (Float) value);
          }
          if (value instanceof Long) {
            builder.addLong(key, (Long) value);
          } else if (value instanceof Double) {
            builder.addDouble(key, (Double) value);
          } else if (value instanceof String) {
            builder.addString(key, (String) value);
          } else if (value instanceof Boolean) {
            builder.addBoolean(key, (Boolean) value);
          } else {
            LOGGER.warn(
                "Invalid key provided in metadata {}:{}",
                key,
                value == null ? "null" : value.getClass());
          }
        });
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private <V> ProviderEvaluation<V> fromError(
      final FeatureFlagEvaluator.EvaluationError error, final V defaultValue) {
    return (ProviderEvaluation<V>)
        ProviderEvaluation.builder()
            .value(defaultValue)
            .errorCode(errorCode(error.getErrorCode()))
            .errorMessage(error.getErrorMessage())
            .reason(FeatureFlagEvaluator.ResolutionReason.ERROR.name())
            .build();
  }

  private static ErrorCode errorCode(final String code) {
    try {
      return ErrorCode.valueOf(code);
    } catch (final IllegalArgumentException e) {
      return ErrorCode.PROVIDER_FATAL;
    }
  }

  private static class ContextAdapter implements FeatureFlagEvaluator.Context {

    private final EvaluationContext delegate;

    private ContextAdapter(final EvaluationContext delegate) {
      this.delegate = delegate;
    }

    @Override
    public String getTargetingKey() {
      return delegate.getTargetingKey();
    }

    @Override
    public Object getValue(final String key) {
      final Value value = delegate.getValue(key);
      return delegate.convertValue(value);
    }

    @Override
    public Set<String> keySet() {
      return delegate.keySet();
    }
  }
}
