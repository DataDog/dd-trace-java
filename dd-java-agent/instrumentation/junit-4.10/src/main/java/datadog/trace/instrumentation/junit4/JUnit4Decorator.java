package datadog.trace.instrumentation.junit4;

import datadog.trace.bootstrap.instrumentation.decorator.TestDecoratorImpl;
import java.nio.file.Path;

public class JUnit4Decorator extends TestDecoratorImpl {
  public JUnit4Decorator(Path currentPath, String testFrameworkVersion) {
    super(currentPath, "junit", "junit4", testFrameworkVersion);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-4"};
  }
}
