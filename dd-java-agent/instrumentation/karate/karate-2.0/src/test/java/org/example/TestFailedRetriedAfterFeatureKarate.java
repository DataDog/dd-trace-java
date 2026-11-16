package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.karatelabs.core.FeatureRunEvent;
import io.karatelabs.core.RunEventType;
import io.karatelabs.core.Runner;
import io.karatelabs.core.ScenarioResult;
import io.karatelabs.core.ScenarioRuntime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Retries failed scenarios once their feature has finished, like a user-defined retry wrapper. */
public class TestFailedRetriedAfterFeatureKarate {

  @Test
  public void test() {
    List<ScenarioResult> retries = new ArrayList<>();
    Runner.path("classpath:org/example/test_retried_after_feature.feature")
        .listener(event -> {
          if (event.getType() == RunEventType.FEATURE_EXIT) {
            FeatureRunEvent featureEvent = (FeatureRunEvent) event;
            for (ScenarioResult result :
                new ArrayList<>(featureEvent.result().getScenarioResults())) {
              if (result.isFailed()) {
                retries.add(
                    new ScenarioRuntime(featureEvent.source(), result.getScenario()).call());
              }
            }
          }
          return true;
        })
        .parallel(1);
    assertEquals(1, retries.size());
    assertTrue(retries.get(0).isPassed());
  }
}
