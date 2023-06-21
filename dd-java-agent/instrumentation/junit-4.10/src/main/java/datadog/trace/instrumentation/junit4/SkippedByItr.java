package datadog.trace.instrumentation.junit4;

import java.lang.annotation.Annotation;
import org.junit.Ignore;

public final class SkippedByItr implements Ignore {
  @Override
  public String value() {
    return "Skipped by Datadog Intelligent Test Runner";
  }

  @Override
  public Class<? extends Annotation> annotationType() {
    return Ignore.class;
  }
}
