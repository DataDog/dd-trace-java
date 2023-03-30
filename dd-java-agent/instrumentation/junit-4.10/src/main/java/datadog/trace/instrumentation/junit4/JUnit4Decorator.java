package datadog.trace.instrumentation.junit4;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.util.Map;

public class JUnit4Decorator extends AbstractTestDecorator {
  public JUnit4Decorator(String testFrameworkVersion, Map<String, String> ciTags) {
    super("junit", "junit4", testFrameworkVersion, ciTags);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-4"};
  }
}
