package datadog.trace.instrumentation.gradle;

import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;

public class GradleDecorator extends TestDecorator {
  public static final GradleDecorator DECORATE = new GradleDecorator();

  @Override
  public String testFramework() {
    return "gradle";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"gradle-build-listener"};
  }

  @Override
  public String component() {
    return "gradle";
  }
}
