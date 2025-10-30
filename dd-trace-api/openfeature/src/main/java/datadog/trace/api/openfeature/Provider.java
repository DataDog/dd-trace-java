package datadog.trace.api.openfeature;

import static datadog.trace.api.featureflag.FeatureFlag.getEvaluator;

import datadog.trace.api.featureflag.FeatureFlag;
import datadog.trace.api.featureflag.FeatureFlagConfiguration;
import datadog.trace.api.featureflag.FeatureFlagEvaluator;
import datadog.trace.api.featureflag.FeatureFlagEvaluator.Resolution;
import datadog.trace.api.featureflag.FeatureFlagEvaluator.ResolutionError;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Provider extends EventProvider
    implements Metadata, Consumer<FeatureFlagConfiguration> {

  private static final Logger LOGGER = LoggerFactory.getLogger(Provider.class);
  private static final String METADATA = "datadog-openfeature-provider";

  private final AtomicBoolean initialized = new AtomicBoolean();

  @Override
  public void initialize(final EvaluationContext evaluationContext) throws Exception {
    FeatureFlag.addConfigListener(this);
  }

  @Override
  public void shutdown() {
    FeatureFlag.removeConfigListener(this);
  }

  @Override
  public void accept(final FeatureFlagConfiguration config) {
    if (!initialized.getAndSet(true)) {
      emit(
          ProviderEvent.PROVIDER_READY,
          ProviderEventDetails.builder().message("Provider ready").build());
    } else {
      emit(
          ProviderEvent.PROVIDER_CONFIGURATION_CHANGED,
          ProviderEventDetails.builder().message("New configuration received").build());
    }
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
    return mapResolution(
        Boolean.class, getEvaluator().evaluate(key, defaultValue, new ContextAdapter(ctx)));
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      final String key, final String defaultValue, final EvaluationContext ctx) {
    return mapResolution(
        String.class, getEvaluator().evaluate(key, defaultValue, new ContextAdapter(ctx)));
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      final String key, final Integer defaultValue, final EvaluationContext ctx) {
    return mapResolution(
        Integer.class, getEvaluator().evaluate(key, defaultValue, new ContextAdapter(ctx)));
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      final String key, final Double defaultValue, final EvaluationContext ctx) {
    return mapResolution(
        Double.class, getEvaluator().evaluate(key, defaultValue, new ContextAdapter(ctx)));
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      final String key, final Value defaultValue, final EvaluationContext ctx) {
    return mapResolution(
        Value.class, getEvaluator().evaluate(key, defaultValue, new ContextAdapter(ctx)));
  }

  private ImmutableMetadata mapFlagMetadata(final Map<String, Object> metadata) {
    if (metadata == null) {
      return null;
    }
    final ImmutableMetadata.ImmutableMetadataBuilder builder = ImmutableMetadata.builder();
    metadata.forEach(
        (key, value) -> {
          if (value instanceof Integer) {
            builder.addInteger(key, (Integer) value);
          } else if (value instanceof Float) {
            builder.addFloat(key, (Float) value);
          } else if (value instanceof Long) {
            builder.addLong(key, (Long) value);
          } else if (value instanceof Double) {
            builder.addDouble(key, (Double) value);
          } else if (value instanceof String) {
            builder.addString(key, (String) value);
          } else if (value instanceof Boolean) {
            builder.addBoolean(key, (Boolean) value);
          } else {
            LOGGER.debug(
                "Invalid key provided in metadata {}:{}",
                key,
                value == null ? "null" : value.getClass());
          }
        });
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private <V, R> ProviderEvaluation<V> mapResolution(
      final Class<V> target, final Resolution<R> result) {
    Object value = result.getValue();
    if (target == Value.class) {
      value = value instanceof Value ? value : Value.objectToValue(value);
    }
    return (ProviderEvaluation<V>)
        ProviderEvaluation.builder()
            .value(value)
            .reason(result.getReason())
            .variant(result.getVariant())
            .flagMetadata(mapFlagMetadata(result.getFlagMetadata()))
            .errorCode(mapErrorCode(result.getErrorCode()))
            .errorMessage(result.getErrorMessage())
            .build();
  }

  private static ErrorCode mapErrorCode(final ResolutionError code) {
    try {
      return ErrorCode.valueOf(code.name());
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
