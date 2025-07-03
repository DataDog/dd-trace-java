package datadog.trace.agent.test;

import datadog.trace.agent.tooling.InstrumenterModule;

public class TestIndexFirstModule extends InstrumenterModule {
  public TestIndexFirstModule() {
    super("test-index-priority");
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.COMMON;
  }

  @Override
  public int order() {
    return -100; // lower-values applied first
  }
}
