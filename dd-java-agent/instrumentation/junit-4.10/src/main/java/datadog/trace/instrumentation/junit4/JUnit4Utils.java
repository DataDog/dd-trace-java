package datadog.trace.instrumentation.junit4;

import java.lang.reflect.Field;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

@Slf4j
public abstract class JUnit4Utils {

  public static List<RunListener> runListenersFromRunNotifier(final RunNotifier runNotifier) {
    try {

      Field listeners;
      try {
        // Since JUnit 4.12, the field is called "listeners"
        listeners = runNotifier.getClass().getDeclaredField("listeners");
      } catch (final NoSuchFieldException e) {
        // Before JUnit 4.12, the field is called "fListeners"
        listeners = runNotifier.getClass().getDeclaredField("fListeners");
      }

      listeners.setAccessible(true);
      return (List<RunListener>) listeners.get(runNotifier);
    } catch (final Throwable e) {
      log.debug("Could not get runListeners for JUnit4Advice", e);
      return null;
    }
  }
}
