package datadog.trace.agent.test;

import static java.util.Arrays.asList;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

public class TestIndexMultiModule extends InstrumenterModule {
  public TestIndexMultiModule() {
    super("test-index-multi");
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.COMMON;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new InstrumenterA(),
        new InstrumenterB(),
        new InstrumenterC(),
        new InstrumenterD(),
        new InstrumenterE());
  }

  static class InstrumenterA implements Instrumenter {}

  static class InstrumenterB implements Instrumenter {}

  static class InstrumenterC implements Instrumenter {}

  static class InstrumenterD implements Instrumenter {}

  static class InstrumenterE implements Instrumenter {}
}
