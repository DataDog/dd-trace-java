package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.util.Map;

public class JUnit5Decorator extends AbstractTestDecorator {
  public JUnit5Decorator(String testFrameworkVersion, Map<String, String> ciTags) {
    super("junit", "junit5", testFrameworkVersion, ciTags);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-5"};
  }
}
