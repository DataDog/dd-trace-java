package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.decorator.TestDecoratorImpl;
import java.nio.file.Path;

public class JUnit5Decorator extends TestDecoratorImpl {
  public JUnit5Decorator(Path currentPath, String testFrameworkVersion) {
    super(currentPath, "junit", "junit5", testFrameworkVersion);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-5"};
  }
}
