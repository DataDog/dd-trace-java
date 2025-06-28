package datadog.trace.instrumentation.graal.nativeimage;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import java.util.Set;

public abstract class AbstractNativeImageInstrumentation extends InstrumenterModule
    implements Instrumenter.HasMethodAdvice {
  public AbstractNativeImageInstrumentation() {
    super("native-image");
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.COMMON;
  }

  @Override
  public boolean isEnabled(Set<TargetSystem> enabledSystems) {
    return Platform.isNativeImageBuilder() && super.isEnabled(enabledSystems);
  }
}
