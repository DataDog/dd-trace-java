package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;

public class JUnit5Decorator extends TestDecorator {

  public static final JUnit5Decorator DECORATE = new JUnit5Decorator();

  @Override
  public String testFramework() {
    return "junit5";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-5"};
  }

  @Override
  public String component() {
    return "junit";
  }
}
