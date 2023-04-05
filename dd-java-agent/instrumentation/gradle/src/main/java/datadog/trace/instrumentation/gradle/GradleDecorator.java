package datadog.trace.instrumentation.gradle;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.nio.file.Path;

public class GradleDecorator extends AbstractTestDecorator {

  public GradleDecorator(Path currentPath) {
    super(currentPath);
  }

  @Override
  public String testFramework() {
    return null;
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
