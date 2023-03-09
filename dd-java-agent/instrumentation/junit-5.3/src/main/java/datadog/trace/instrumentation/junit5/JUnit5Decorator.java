package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.nio.file.Paths;

public class JUnit5Decorator extends AbstractTestDecorator {

  public static final JUnit5Decorator DECORATE = new JUnit5Decorator();

  public JUnit5Decorator() {
    super(Paths.get("").toAbsolutePath());
  }

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
