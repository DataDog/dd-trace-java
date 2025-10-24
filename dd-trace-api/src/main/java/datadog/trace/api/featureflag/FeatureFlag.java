package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.noop.NoOpFeatureFlagEvaluator;

public abstract class FeatureFlag {

  public static FeatureFlagEvaluator EVALUATOR = new NoOpFeatureFlagEvaluator();

  private FeatureFlag() {}
}
