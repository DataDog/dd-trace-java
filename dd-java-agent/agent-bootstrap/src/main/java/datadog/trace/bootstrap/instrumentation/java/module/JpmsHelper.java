package datadog.trace.bootstrap.instrumentation.java.module;

import static java.util.Collections.unmodifiableSet;

import datadog.trace.api.GenericClassValue;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NotThreadSafe
public final class JpmsHelper {
  private JpmsHelper() {}

  private static final Set<String> TRIGGERS = new HashSet<>();

  private static final ClassValue<AtomicBoolean> HAS_FIRED =
      GenericClassValue.constructing(AtomicBoolean.class);

  public static final Logger LOGGER = LoggerFactory.getLogger(JpmsHelper.class);

  public static void addTriggers(Collection<String> classes) {
    if (classes == null) {
      return;
    }
    TRIGGERS.addAll(classes);
  }

  public static Set<String> getAllTriggers() {
    return unmodifiableSet(TRIGGERS);
  }

  public static boolean shouldBeOpened(Class<?> cls) {
    return HAS_FIRED.get(cls).compareAndSet(false, true);
  }
}
