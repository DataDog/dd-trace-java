import static org.junit.jupiter.api.Assertions.assertTrue;

import io.karatelabs.common.Resource;
import io.karatelabs.core.KarateJs;
import io.karatelabs.core.ScenarioResult;
import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Scenario;
import org.junit.jupiter.api.Test;

public class KarateStandaloneScenarioTest {

  @Test
  public void testStandaloneScenarioRuntime() {
    Resource resource = Resource.path("classpath:org/example/test_succeed_one_case.feature");
    Feature feature = Feature.read(resource);
    Scenario scenario = feature.getSections().getFirst().getScenario();

    ScenarioResult result = new ScenarioRuntime(new KarateJs(resource), scenario).call();

    assertTrue(result.isPassed());
  }
}
