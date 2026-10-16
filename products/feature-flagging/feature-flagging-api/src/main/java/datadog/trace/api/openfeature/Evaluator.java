package datadog.trace.api.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import java.util.concurrent.TimeUnit;

interface Evaluator {

  boolean initialize(long timeout, TimeUnit timeUnit, EvaluationContext context) throws Exception;

  boolean hasConfiguration();

  void shutdown();

  <T> ProviderEvaluation<T> evaluate(
      Class<T> target, String key, T defaultValue, EvaluationContext context);

  default <T> ProviderEvaluation<T> evaluate(
      Class<T> target,
      String key,
      T defaultValue,
      EvaluationContext context,
      boolean experimentationOptOut) {
    if (experimentationOptOut) {
      return ProviderEvaluation.<T>builder()
          .value(defaultValue)
          .reason(Reason.ERROR.name())
          .errorCode(ErrorCode.GENERAL)
          .errorMessage("The evaluator does not support experimentation opt-out")
          .build();
    }
    return evaluate(target, key, defaultValue, context);
  }
}
