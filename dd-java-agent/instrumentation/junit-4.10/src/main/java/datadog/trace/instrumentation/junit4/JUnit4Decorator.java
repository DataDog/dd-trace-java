package datadog.trace.instrumentation.junit4;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.nio.file.Paths;

public class JUnit4Decorator extends AbstractTestDecorator {
  public static final JUnit4Decorator DECORATE = new JUnit4Decorator();

  public JUnit4Decorator() {
    super(Paths.get("").toAbsolutePath());
  }

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
