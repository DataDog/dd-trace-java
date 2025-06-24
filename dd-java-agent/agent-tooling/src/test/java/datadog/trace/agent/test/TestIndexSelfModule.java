package datadog.trace.agent.test;

import datadog.trace.agent.tooling.InstrumenterModule;

public class TestIndexSelfModule extends InstrumenterModule {
  public TestIndexSelfModule() {
    super("test-index-self");
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.COMMON;
  }
}
