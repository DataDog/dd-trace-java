package datadog.trace.civisibility.domain.buildsystem;

import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ErrorResponse;
import datadog.trace.civisibility.ipc.ModuleSignal;
import datadog.trace.civisibility.ipc.Signal;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatches received signals to the right handlers depending on module ID. */
public class ModuleSignalRouter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ModuleSignalRouter.class);

  private final Map<Long, Map<SignalType, Function<Signal, SignalResponse>>> moduleHandlersById;

  public ModuleSignalRouter() {
    this.moduleHandlersById = new ConcurrentHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public <T extends Signal> void registerModuleHandler(
      Long moduleId, SignalType signalType, Function<T, SignalResponse> handler) {
    moduleHandlersById
        .computeIfAbsent(moduleId, m -> new ConcurrentHashMap<>())
        .put(signalType, (Function<Signal, SignalResponse>) handler);
  }

  public void removeModuleHandlers(Long moduleId) {
    moduleHandlersById.remove(moduleId);
  }

  public SignalResponse onModuleSignalReceived(ModuleSignal result) {
    long moduleId = result.getModuleId();
    Map<SignalType, Function<Signal, SignalResponse>> handlersByType =
        moduleHandlersById.get(moduleId);
    if (handlersByType == null) {
      String message =
          String.format(
              "Could not find signal handlers for module ID %s, test execution result will be ignored: %s",
              moduleId, result);
      LOGGER.warn(message);
      return new ErrorResponse(message);
    }

    Function<Signal, SignalResponse> moduleHandler = handlersByType.get(result.getType());
    if (moduleHandler != null) {
      return moduleHandler.apply(result);
    }

    return AckResponse.INSTANCE;
  }
}
