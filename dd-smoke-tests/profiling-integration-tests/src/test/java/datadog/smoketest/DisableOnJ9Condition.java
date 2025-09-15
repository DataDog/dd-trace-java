package datadog.smoketest;

import static datadog.environment.JavaVirtualMachine.isJ9;
import static datadog.environment.JavaVirtualMachine.isJavaVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;

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
    return !isJ9()
        || (isJavaVersion(8) && isJavaVersion(8, 0, 361))
        || isJavaVersionAtLeast(11, 0, 18)
        || isJavaVersionAtLeast(17, 0, 6);
  }
}
