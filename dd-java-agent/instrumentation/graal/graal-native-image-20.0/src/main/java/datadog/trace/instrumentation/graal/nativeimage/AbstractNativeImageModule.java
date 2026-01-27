package datadog.trace.instrumentation.graal.nativeimage;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import java.util.Set;

public abstract class AbstractNativeImageModule extends InstrumenterModule {
  public AbstractNativeImageModule() {
    super("native-image");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    // this is a cross product module, and it's always enabled apriori.
    // The enablement is done at runtime via `isEnabled()`
    return true;
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Platform.isNativeImageBuilder();
  }
}
