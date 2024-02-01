package datadog.trace.instrumentation.graal.nativeimage;

import datadog.trace.agent.tooling.InstrumenterGroup;
import datadog.trace.api.Platform;
import java.util.Set;

public abstract class AbstractNativeImageInstrumentation extends InstrumenterGroup {
  public AbstractNativeImageInstrumentation() {
    super("native-image");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return Platform.isNativeImageBuilder();
  }
}
