package datadog.trace.instrumentation.testng;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.util.Map;

public class TestNGDecorator extends AbstractTestDecorator {

  public TestNGDecorator(String version, Map<String, String> ciTags) {
    super("testng", "testng", version, ciTags);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"testng"};
  }
}
