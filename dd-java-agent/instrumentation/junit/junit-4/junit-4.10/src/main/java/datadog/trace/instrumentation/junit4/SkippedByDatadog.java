package datadog.trace.instrumentation.junit4;

import java.lang.annotation.Annotation;
import org.junit.Ignore;

public final class SkippedByDatadog implements Ignore {

  private final String description;

  public SkippedByDatadog(String description) {
    this.description = description;
  }

  @Override
  public String value() {
    return description;
  }

  @Override
  public Class<? extends Annotation> annotationType() {
    return Ignore.class;
  }
}
