package datadog.trace.instrumentation.testng;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.nio.file.Paths;

public class TestNGDecorator extends AbstractTestDecorator {
  public static final TestNGDecorator DECORATE = new TestNGDecorator();

  public TestNGDecorator() {
    super(Paths.get("").toAbsolutePath());
  }

  @Override
  public String testFramework() {
    return "testng";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"testng"};
  }

  @Override
  public String component() {
    return "testng";
  }
}
