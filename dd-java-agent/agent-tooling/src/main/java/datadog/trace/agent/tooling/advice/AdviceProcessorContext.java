package datadog.trace.agent.tooling.advice;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.dynamic.DynamicType;

/** Mutable pipeline context shared by ordered advice processors for one module. */
public final class AdviceProcessorContext {
  private final InstrumenterModule module;
  private final File targetDirectory;
  private final Map<Class<?>, Object> results = new HashMap<>();
  private DynamicType.Builder<?> builder;

  AdviceProcessorContext(
      InstrumenterModule module, File targetDirectory, DynamicType.Builder<?> builder) {
    this.module = module;
    this.targetDirectory = targetDirectory;
    this.builder = builder;
  }

  public InstrumenterModule getModule() {
    return module;
  }

  public File getTargetDirectory() {
    return targetDirectory;
  }

  public DynamicType.Builder<?> getBuilder() {
    return builder;
  }

  public void setBuilder(DynamicType.Builder<?> builder) {
    this.builder = builder;
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
