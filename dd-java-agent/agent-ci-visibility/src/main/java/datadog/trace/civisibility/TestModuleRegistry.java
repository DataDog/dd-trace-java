package datadog.trace.civisibility;

import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ModuleExecutionResult;
import datadog.trace.civisibility.ipc.SignalResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestModuleRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestModuleRegistry.class);

  private final Map<Long, DDBuildSystemModule> testModuleById;

  public TestModuleRegistry() {
    this.testModuleById = new ConcurrentHashMap<>();
  }

  public void addModule(DDBuildSystemModule module) {
    testModuleById.put(module.getId(), module);
  }

  public SignalResponse onModuleExecutionResultReceived(
      ModuleExecutionResult result, ExecutionDataStore coverageData) {
    long moduleId = result.getModuleId();
    DDBuildSystemModule module = testModuleById.remove(moduleId);
    if (module == null) {
      String message =
          String.format(
              "Could not find module with ID %s, test execution result will be ignored: %s",
              moduleId, result);
      LOGGER.warn(message);
      return new ErrorResponse(message);
    }

    module.onModuleExecutionResultReceived(result, coverageData);
    return AckResponse.INSTANCE;
  }
}
