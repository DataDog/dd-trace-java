package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.noop.NoOpFeatureFlagEvaluator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public abstract class FeatureFlag {

  private static FeatureFlagEvaluator EVALUATOR = new NoOpFeatureFlagEvaluator();

  private static final List<Consumer<FeatureFlagConfiguration>> CONFIG_LISTENERS =
      new CopyOnWriteArrayList<>();

  private FeatureFlag() {}

  public static void init(final FeatureFlagEvaluator evaluator) {
    EVALUATOR = evaluator;
  }

  public static FeatureFlagEvaluator getEvaluator() {
    return EVALUATOR;
  }

  public static void addConfigListener(final Consumer<FeatureFlagConfiguration> listener) {
    CONFIG_LISTENERS.add(listener);
  }

  public static void removeConfigListener(final Consumer<FeatureFlagConfiguration> listener) {
    CONFIG_LISTENERS.remove(listener);
  }

  public static List<Consumer<FeatureFlagConfiguration>> getConfigListeners() {
    return CONFIG_LISTENERS;
  }
}
