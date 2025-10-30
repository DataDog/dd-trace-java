package datadog.trace.api.featureflag.noop;

import datadog.trace.api.featureflag.FeatureFlagEvaluator;

public class NoOpFeatureFlagEvaluator implements FeatureFlagEvaluator {

  public static final NoOpFeatureFlagEvaluator INSTANCE = new NoOpFeatureFlagEvaluator();

  @Override
  public Resolution<Boolean> evaluate(
      final String key, final Boolean defaultValue, final Context context) {
    return Resolution.providerNotReady(key, defaultValue);
  }

  @Override
  public Resolution<Integer> evaluate(
      final String key, final Integer defaultValue, final Context context) {
    return Resolution.providerNotReady(key, defaultValue);
  }

  @Override
  public Resolution<Double> evaluate(
      final String key, final Double defaultValue, final Context context) {
    return Resolution.providerNotReady(key, defaultValue);
  }

  @Override
  public Resolution<String> evaluate(
      final String key, final String defaultValue, final Context context) {
    return Resolution.providerNotReady(key, defaultValue);
  }

  @Override
  public Resolution<Object> evaluate(
      final String key, final Object defaultValue, final Context context) {
    return Resolution.providerNotReady(key, defaultValue);
  }
}
