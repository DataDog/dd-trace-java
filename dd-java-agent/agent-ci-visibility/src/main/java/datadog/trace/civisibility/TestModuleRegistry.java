package datadog.trace.civisibility;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestModuleRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestModuleRegistry.class);

  private final Map<Long, DDTestModuleImpl> testModuleById;

  public TestModuleRegistry() {
    this.testModuleById = new HashMap<>();
  }

  public void addModule(DDTestModuleImpl module) {
    testModuleById.put(module.getId(), module);
  }

  public void removeModule(DDTestModuleImpl module) {
    testModuleById.remove(module.getId());
  }

  public void onModuleExecutionResultReceived(ModuleExecutionResult result) {
    long moduleId = result.getModuleId();
    DDTestModuleImpl module = testModuleById.get(moduleId);
    if (module == null) {
      LOGGER.warn(
          "Could not find module with ID {}, test execution result will be ignored: {}",
          moduleId,
          result);
      return;
    }

    if (result.isCoverageEnabled()) {
      module.setTag(Tags.TEST_CODE_COVERAGE_ENABLED, true);
    }
    if (result.isItrEnabled()) {
      module.setTag(Tags.TEST_ITR_TESTS_SKIPPING_ENABLED, true);
    }
    if (result.isItrTestsSkipped()) {
      module.setTag(DDTags.CI_ITR_TESTS_SKIPPED, true);
    }
  }
}
