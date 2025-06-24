package datadog.trace.agent.test;

import datadog.trace.agent.tooling.InstrumenterModule;

public class TestIndexLastModule extends InstrumenterModule {
  public TestIndexLastModule() {
    super("test-index-priority");
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.COMMON;
  }

  @Override
  public int order() {
    return 100; // higher-values applied last
  }
}
