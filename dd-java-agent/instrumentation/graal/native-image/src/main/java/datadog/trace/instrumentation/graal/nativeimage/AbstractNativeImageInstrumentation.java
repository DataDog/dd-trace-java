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
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return Platform.isNativeImageBuilder();
  }
}
