package datadog.smoketest;

import datadog.trace.api.Platform;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class DisableOnJ9Condition implements ExecutionCondition {
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    return Platform.isJ9()
        ? ConditionEvaluationResult.disabled("Profiling context is not supported for J9")
        : ConditionEvaluationResult.enabled("");
  }
}
