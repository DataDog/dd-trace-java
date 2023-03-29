package datadog.trace.instrumentation.testng;

import datadog.trace.bootstrap.instrumentation.decorator.TestDecoratorImpl;
import java.nio.file.Path;

public class TestNGDecorator extends TestDecoratorImpl {

  public TestNGDecorator(Path currentPath, String testFrameworkVersion) {
    super(currentPath, "testng", "testng", testFrameworkVersion);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"testng"};
  }
}
