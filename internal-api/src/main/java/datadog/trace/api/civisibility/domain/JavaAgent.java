package datadog.trace.api.civisibility.domain;

import java.io.Serializable;

public class JavaAgent implements Serializable {
  private final String path;
  private final String arguments;

  public JavaAgent(String path, String arguments) {
    this.path = path;
    this.arguments = arguments;
  }

  public String getPath() {
    return path;
  }

  public String getArguments() {
    return arguments;
  }
}
