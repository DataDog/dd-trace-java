package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.noop.NoOpFeatureFlagEvaluator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public abstract class FeatureFlag {

  private static FeatureFlagEvaluator EVALUATOR = new NoOpFeatureFlagEvaluator();

  private static final List<FeatureFlagConfigListener> CONFIG_LISTENERS =
      new CopyOnWriteArrayList<>();

  private static final AtomicReference<FeatureFlagConfig> CURRENT_CONFIG = new AtomicReference<>();

  public static FeatureFlagEvaluator getEvaluator() {
    return EVALUATOR;
  }

  public static void setEvaluator(final FeatureFlagEvaluator evaluator) {
    EVALUATOR = evaluator;
  }

  public static void dispatch(final FeatureFlagConfig config) {
    CURRENT_CONFIG.set(config);
    CONFIG_LISTENERS.forEach(listener -> listener.onConfigurationChanged(config));
  }

  public static void addListener(final FeatureFlagConfigListener listener) {
    final FeatureFlagConfig current = CURRENT_CONFIG.get();
    if (current != null) {
      listener.onConfigurationChanged(current);
    }
    CONFIG_LISTENERS.add(listener);
  }

  public static void removeListener(final FeatureFlagConfigListener listener) {
    CONFIG_LISTENERS.remove(listener);
  }
}
