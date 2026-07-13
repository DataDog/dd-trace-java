package datadog.smoketest.backend;

import static org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled;
import static org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit {@link ExecutionCondition} backing {@link EnabledIfDockerAvailable}. Docker availability is
 * probed once per JVM and cached, since the probe can be slow.
 */
final class DockerAvailableCondition implements ExecutionCondition {
  private static volatile Boolean dockerAvailable;

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (isDockerAvailable()) {
      return enabled("Docker is available");
    }
    return disabled(
        "Docker is not available — skipping test-agent container test. Start Docker to run it "
            + "locally, or reuse a CI test agent via CI_AGENT_HOST.");
  }

  private static boolean isDockerAvailable() {
    Boolean cached = dockerAvailable;
    if (cached == null) {
      synchronized (DockerAvailableCondition.class) {
        cached = dockerAvailable;
        if (cached == null) {
          try {
            cached = DockerClientFactory.instance().isDockerAvailable();
          } catch (Throwable t) {
            // Any failure probing the daemon (Testcontainers/docker-java errors) => treat as
            // absent.
            cached = false;
          }
          dockerAvailable = cached;
        }
      }
    }
    return cached;
  }
}
