package datadog.smoketest;

import datadog.trace.api.Platform;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class DisableOnJ9Condition implements ExecutionCondition {
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    return isDdprofSupported()
        ? ConditionEvaluationResult.enabled("")
        : ConditionEvaluationResult.disabled("Profiling context is not supported for J9");
  }

  private static boolean isDdprofSupported() {
    return !Platform.isJ9()
        || (Platform.isJavaVersion(8) && Platform.isJavaVersion(8, 0, 361))
        || Platform.isJavaVersionAtLeast(11, 0, 18)
        || Platform.isJavaVersionAtLeast(17, 0, 6);
  }
}
