package datadog.smoketest.backend;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;

/**
 * Verifies the {@link EnabledIfDockerAvailable} condition evaluates cleanly regardless of the
 * Docker state — probing failures are swallowed and a reason is always attached. Whether it enables
 * or disables is exercised end-to-end by {@link TestAgentBackendContainerTest} (which carries the
 * annotation): it runs when Docker is present, and is skipped otherwise.
 */
class DockerAvailableConditionTest {

  @Test
  void evaluatesToAResultWithAReason() {
    ConditionEvaluationResult result =
        new DockerAvailableCondition().evaluateExecutionCondition(null);

    assertNotNull(result, "condition returns a result without throwing");
    assertTrue(result.getReason().isPresent(), "both the enabled and disabled paths give a reason");
  }
}
