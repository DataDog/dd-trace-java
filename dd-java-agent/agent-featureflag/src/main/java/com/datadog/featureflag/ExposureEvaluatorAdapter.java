package com.datadog.featureflag;

import datadog.trace.api.featureflag.FeatureFlagEvaluator;

public class ExposureEvaluatorAdapter implements FeatureFlagEvaluator {

  private final ExposureWriter writer;
  private final FeatureFlagEvaluator delegate;

  public ExposureEvaluatorAdapter(
      final ExposureWriter writer, final FeatureFlagEvaluator delegate) {
    this.writer = writer;
    this.delegate = delegate;
  }

  @Override
  public void addListener(final Listener listener) {
    delegate.addListener(listener);
  }

  @Override
  public Resolution<Boolean> evaluate(
      final String key, final Boolean defaultValue, final Context context) {
    return handleResolution(key, context, delegate.evaluate(key, defaultValue, context));
  }

  @Override
  public Resolution<Integer> evaluate(
      final String key, final Integer defaultValue, final Context context) {
    return handleResolution(key, context, delegate.evaluate(key, defaultValue, context));
  }

  @Override
  public Resolution<Double> evaluate(
      final String key, final Double defaultValue, final Context context) {
    return handleResolution(key, context, delegate.evaluate(key, defaultValue, context));
  }

  @Override
  public Resolution<String> evaluate(
      final String key, final String defaultValue, final Context context) {
    return handleResolution(key, context, delegate.evaluate(key, defaultValue, context));
  }

  @Override
  public Resolution<Object> evaluate(
      final String key, final Object defaultValue, final Context context) {
    return handleResolution(key, context, delegate.evaluate(key, defaultValue, context));
  }

  private <E> Resolution<E> handleResolution(
      final String key, final Context context, final Resolution<E> resolution) {
    writer.write(key, context, resolution);
    return resolution;
  }
}
