package datadog.trace.bootstrap.instrumentation.java.module;

import static java.util.Collections.unmodifiableSet;

import datadog.trace.api.GenericClassValue;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NotThreadSafe
public class JpmsHelper {
  private JpmsHelper() {}

  private static final Set<String> TRIGGERS = new HashSet<>();

  private static final ClassValue<AtomicBoolean> CLSVALUE =
      GenericClassValue.constructing(AtomicBoolean.class);

  public static final Logger LOGGER = LoggerFactory.getLogger(JpmsHelper.class);

  public static void addAllTriggers(Iterable<String> classes) {
    if (classes == null) {
      return;
    }
    for (String cls : classes) {
      TRIGGERS.add(cls);
    }
  }

  public static Set<String> getAllTriggers() {
    return unmodifiableSet(TRIGGERS);
  }

  public static boolean shouldBeOpened(Class<?> cls) {
    return CLSVALUE.get(cls).compareAndSet(false, true);
  }
}
