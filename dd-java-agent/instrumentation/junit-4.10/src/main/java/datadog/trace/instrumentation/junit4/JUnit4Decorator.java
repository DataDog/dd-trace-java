package datadog.trace.instrumentation.junit4;

import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;

public class JUnit4Decorator extends TestDecorator {
  public static final JUnit4Decorator DECORATE = new JUnit4Decorator();

  @Override
  public String testFramework() {
    return "junit4";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-4", "junit-4-suite-events"};
  }

  @Override
  public String component() {
    return "junit";
  }
}
