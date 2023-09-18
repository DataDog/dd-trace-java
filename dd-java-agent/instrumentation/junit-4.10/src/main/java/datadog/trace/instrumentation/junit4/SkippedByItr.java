package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.InstrumentationBridge;
import java.lang.annotation.Annotation;
import org.junit.Ignore;

public final class SkippedByItr implements Ignore {
  @Override
  public String value() {
    return InstrumentationBridge.ITR_SKIP_REASON;
  }

  @Override
  public Class<? extends Annotation> annotationType() {
    return Ignore.class;
  }
}
