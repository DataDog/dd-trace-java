package datadog.trace.agent.test.base;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/** Skeleton single-class test instrumentation. */
public abstract class TestInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public TestInstrumentation() {
    super("test");
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.COMMON;
  }
}
