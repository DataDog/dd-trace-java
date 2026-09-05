package datadog.trace.agent.tooling.advice;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Mutable pipeline context shared by ordered advice processors for one module. */
public final class AdviceProcessorContext {
  private final InstrumenterModule module;
  private final File targetDirectory;
  private final Map<Class<?>, Object> results = new HashMap<>();

  AdviceProcessorContext(InstrumenterModule module, File targetDirectory) {
    this.module = module;
    this.targetDirectory = targetDirectory;
  }

  public InstrumenterModule getModule() {
    return module;
  }

  public File getTargetDirectory() {
    return targetDirectory;
  }

  public <T> T getResult(Class<T> resultType) {
    Object result = results.get(resultType);
    if (result == null) {
      throw new IllegalStateException("No advice processor result of type " + resultType.getName());
    }
    return resultType.cast(result);
  }

  <T> void putResult(Class<T> resultType, T result) {
    if (results.put(resultType, result) != null) {
      throw new IllegalStateException(
          "Duplicate advice processor result type " + resultType.getName());
    }
  }
}
