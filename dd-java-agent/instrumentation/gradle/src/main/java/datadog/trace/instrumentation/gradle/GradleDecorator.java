package datadog.trace.instrumentation.gradle;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.util.Map;

public class GradleDecorator extends AbstractTestDecorator {

  public GradleDecorator(Map<String, String> ciTags) {
    super("gradle", null, null, ciTags);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"gradle", "gradle-build-listener"};
  }
}
