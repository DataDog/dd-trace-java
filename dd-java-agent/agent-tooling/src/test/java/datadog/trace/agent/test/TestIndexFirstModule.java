package datadog.trace.agent.test;

import datadog.trace.agent.tooling.InstrumenterModule;

public class TestIndexFirstModule extends InstrumenterModule {
  public TestIndexFirstModule() {
    super("test-index-priority");
  }

  @Override
  public int order() {
    // lower-values applied first
    return -100;
  }
}
