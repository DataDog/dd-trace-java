package datadog.trace.civisibility;

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

  private final Map<Long, DDTestModuleParent> testModuleById;

  public TestModuleRegistry() {
    this.testModuleById = new ConcurrentHashMap<>();
  }

  public void addModule(DDTestModuleParent module) {
    testModuleById.put(module.getId(), module);
  }

  public void removeModule(DDTestModuleParent module) {
    testModuleById.remove(module.getId());
  }

  public SignalResponse onModuleExecutionResultReceived(ModuleExecutionResult result) {
    long moduleId = result.getModuleId();
    DDTestModuleParent module = testModuleById.get(moduleId);
    if (module == null) {
      String message =
          String.format(
              "Could not find module with ID %s, test execution result will be ignored: %s",
              moduleId, result);
      LOGGER.warn(message);
      return new ErrorResponse(message);
    }
    module.onModuleExecutionResultReceived(result);
    return AckResponse.INSTANCE;
  }
}
