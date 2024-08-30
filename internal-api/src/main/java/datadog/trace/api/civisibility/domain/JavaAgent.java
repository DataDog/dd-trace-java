package datadog.trace.api.civisibility.domain;

import java.io.Serializable;
import javax.annotation.Nullable;

public class JavaAgent implements Serializable {

  private final String path;

  @Nullable private final String arguments;

  public JavaAgent(String path, @Nullable String arguments) {
    this.path = path;
    this.arguments = arguments;
  }

  public String getPath() {
    return path;
  }

  @Nullable
  public String getArguments() {
    return arguments;
  }
}
