package datadog.trace.instrumentation.maven3;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.util.Map;

public class MavenDecorator extends AbstractTestDecorator {

  public MavenDecorator(Map<String, String> ciTags) {
    super("maven", null, null, ciTags);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"maven"};
  }
}
