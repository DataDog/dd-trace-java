package datadog.trace.bootstrap.instrumentation.jpms;

import datadog.trace.api.GenericClassValue;
import java.util.concurrent.atomic.AtomicBoolean;

public class JpmsAdvisingHelper {
  public static final ClassValue<AtomicBoolean> ALREADY_PROCESSED_CACHE =
      GenericClassValue.constructing(AtomicBoolean.class);

  private JpmsAdvisingHelper() {}
}
