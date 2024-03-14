package datadog.trace.agent.test.base;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Set;

/** Skeleton single-class test instrumentation. */
public abstract class TestInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public TestInstrumentation() {
    super("test");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return true; // always on for testing purposes
  }
}
