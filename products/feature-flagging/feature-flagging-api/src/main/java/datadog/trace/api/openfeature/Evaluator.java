package datadog.trace.api.openfeature;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.concurrent.TimeUnit;

interface Evaluator {

  boolean initialize(long timeout, TimeUnit timeUnit, EvaluationContext context) throws Exception;

  boolean hasConfiguration();

  void shutdown();

  <T> ProviderEvaluation<T> evaluate(
      Class<T> target, String key, T defaultValue, EvaluationContext context);
}
