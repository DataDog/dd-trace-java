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

  private static final Set<String> TRIGGERS_VIEW = unmodifiableSet(TRIGGERS);

  private static final ClassValue<AtomicBoolean> HAS_FIRED =
      GenericClassValue.constructing(AtomicBoolean.class);

  private static final Logger LOGGER = LoggerFactory.getLogger(JpmsHelper.class);

  /**
   * Registers trigger class names whose constructors will open their enclosing module. Must be
   * called at agent startup before instrumentation is applied; not thread-safe.
   */
  public static void addTriggers(Collection<String> classes) {
    if (classes == null) {
      return;
    }
    TRIGGERS.addAll(classes);
  }

  /** Returns an unmodifiable view of all registered trigger class names. */
  public static Set<String> getAllTriggers() {
    return TRIGGERS_VIEW;
  }

  /**
   * Returns {@code true} and atomically marks {@code cls} as opened the first time this is called
   * for a given class; returns {@code false} on all subsequent calls for the same class.
   */
  public static boolean shouldBeOpened(Class<?> cls) {
    return HAS_FIRED.get(cls).compareAndSet(false, true);
  }

  /** Called from inlined ByteBuddy advice; logs when module opening fails. */
  public static void logFailedToOpen(String pkg, Throwable t) {
    LOGGER.debug("Unable to open package {} to the agent module or unnamed module", pkg, t);
  }

  /** Called from inlined ByteBuddy advice; logs when a class has no named module. */
  public static void logNoNamedModule(Class<?> cls) {
    LOGGER.debug("{} has no named module; skipping module open", cls);
  }
}
