package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the JFR startup-deadlock workaround in {@link Agent}.
 *
 * <p>{@link Agent#initializeJfrEventHolderClass(ClassLoader)} force-initializes the JDK's JFR
 * event-holder class ({@code jdk.jfr.events.Handlers} on JDK 17-18, {@code
 * jdk.jfr.events.EventConfigurations} on JDK 19-22) to avoid an ABBA deadlock. Its static-final
 * handler fields are populated from {@code jdk.jfr.internal.Utils} at {@code <clinit>} time and
 * stay {@code null} forever if the class is initialized before the JDK's built-in events are
 * registered (i.e. before {@code FlightRecorder} initialization). A null-poisoned holder silently
 * disables the built-in socket/file/exception JFR events.
 *
 * <p>This test runs the production initialization path and asserts none of the handler fields were
 * poisoned. It is a {@code ForkedTest} because JFR/class initialization happens once per JVM, so
 * the ordering under test is only exercised in a fresh JVM that has not yet touched JFR.
 *
 * <p>Requires {@code --add-opens jdk.jfr/jdk.jfr.events=ALL-UNNAMED} (configured in build.gradle)
 * to read the holder's fields reflectively. Automatically skipped on JDKs without the holder class
 * (JDK 8, and JDK 23+ where the eager-init pattern was removed).
 */
public class JfrEventHolderInitForkedTest {

  private static final String[] HOLDER_CLASS_NAMES = {
    "jdk.jfr.events.Handlers", // JDK 17-18
    "jdk.jfr.events.EventConfigurations" // JDK 19-22
  };

  @Test
  public void productionInitOrderingDoesNotPoisonHandlers() throws Exception {
    final ClassLoader loader = getClass().getClassLoader();

    // Find the holder class for this JDK without initializing it; skip if none exists (JDK 8, 23+).
    final String holderName = findHolderClass(loader);
    assumeTrue(holderName != null, "No JFR event-holder class on this JDK; nothing to test");

    // Exercise the exact production path: FlightRecorder init first, then holder <clinit>.
    Agent.initializeJfrEventHolderClass(loader);

    // The holder is now initialized; read its static handler fields and check none are null.
    final Class<?> holder = Class.forName(holderName, false, loader);
    final List<String> nullFields = new ArrayList<>();
    int handlerFields = 0;
    for (final Field field : holder.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
        continue;
      }
      handlerFields++;
      field.setAccessible(true);
      if (field.get(null) == null) {
        nullFields.add(field.getName());
      }
    }

    assertTrue(handlerFields > 0, "expected " + holderName + " to declare handler fields");
    assertTrue(
        nullFields.isEmpty(),
        "JFR handler fields were poisoned to null (holder initialized before FlightRecorder): "
            + nullFields);
  }

  private static String findHolderClass(final ClassLoader loader) {
    for (final String name : HOLDER_CLASS_NAMES) {
      try {
        Class.forName(name, false, loader); // load only, do not initialize
        return name;
      } catch (final Throwable ignored) {
        // not present on this JDK; try the next name
      }
    }
    return null;
  }
}
