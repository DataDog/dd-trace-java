package datadog.trace.instrumentation.maven3;

import datadog.trace.bootstrap.instrumentation.decorator.AbstractTestDecorator;
import java.nio.file.Path;

public class MavenDecorator extends AbstractTestDecorator {

  public MavenDecorator(Path currentPath) {
    super(currentPath);
  }

  @Override
  public String testFramework() {
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"maven"};
  }

  @Override
  public String component() {
    return "maven";
  }
}
