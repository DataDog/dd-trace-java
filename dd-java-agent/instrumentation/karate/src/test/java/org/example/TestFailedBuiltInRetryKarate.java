package org.example;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioResult;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

@DisabledForJreRange(
    min = JRE.JAVA_24,
    disabledReason =
        "Karate does not support Java 24+ yet: https://github.com/karatelabs/karate/blob/master/.github/workflows/jdk-compat.yml#L18")
public class TestFailedBuiltInRetryKarate {

  @Test
  public void testSucceed() {
    Results results = Runner.path("classpath:org/example/test_failed.feature").parallel(1);

    List<ScenarioResult> failed =
        results.getScenarioResults().filter(ScenarioResult::isFailed).collect(Collectors.toList());
    for (ScenarioResult scenarioResult : failed) {
      Scenario scenario = scenarioResult.getScenario();
      results.getSuite().retryScenario(scenario);
    }
  }
}
