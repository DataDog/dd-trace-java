package datadog.trace.api.openfeature.evaluator;

import datadog.trace.api.openfeature.config.ServerConfigurationListener;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ProviderEvaluation;

public interface FeatureFlagEvaluator extends ServerConfigurationListener {

  <T> ProviderEvaluation<T> evaluate(
      Class<T> target, String key, T defaultValue, EvaluationContext context);
}
