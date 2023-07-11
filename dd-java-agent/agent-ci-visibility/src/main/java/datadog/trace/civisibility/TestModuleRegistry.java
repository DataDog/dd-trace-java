package datadog.trace.civisibility;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestModuleRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestModuleRegistry.class);

  private final Map<Long, DDTestModuleImpl> testModuleById;

  public TestModuleRegistry() {
    this.testModuleById = new ConcurrentHashMap<>();
  }

  public void addModule(DDTestModuleImpl module) {
    testModuleById.put(module.getId(), module);
  }

  public void removeModule(DDTestModuleImpl module) {
    testModuleById.remove(module.getId());
  }

  public SignalResponse onModuleExecutionResultReceived(ModuleExecutionResult result) {
    long moduleId = result.getModuleId();
    DDTestModuleImpl module = testModuleById.get(moduleId);
    if (module == null) {
      String message =
          String.format(
              "Could not find module with ID %s, " + "test execution result will be ignored: %s",
              moduleId, result);
      LOGGER.warn(message);
      return new ErrorResponse(message);
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
    return AckResponse.INSTANCE;
  }
}
