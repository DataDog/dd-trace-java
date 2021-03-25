package datadog.trace.instrumentation.junit4;

import java.lang.reflect.Field;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JUnit4Utils {

  private static final Logger log = LoggerFactory.getLogger(JUnit4Utils.class);
  private static final String SYNCHRONIZED_LISTENER =
      "org.junit.runner.notification.SynchronizedRunListener";

  // Regex for the final brackets with its content in the test name. E.g. test_name[0] --> [0]
  private static final Pattern testNameNormalizerRegex = Pattern.compile("\\[[^\\[]*\\]$");

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

  public static boolean isTracingListener(final RunListener listener) {
    if (listener instanceof TracingListener) {
      return true;
    }

    // Since JUnit 4.12, the RunListener are wrapped by a SynchronizedRunListener object.
    if (SYNCHRONIZED_LISTENER.equals(listener.getClass().getName())) {
      try {
        // There is no public accessor to the inner listener.
        final Field innerListener = listener.getClass().getDeclaredField("listener");
        innerListener.setAccessible(true);

        return innerListener.get(listener) instanceof TracingListener;
      } catch (final Throwable e) {
        log.debug("Could not get inner listener from SynchronizedRunListener for JUnit4Advice", e);
        return false;
      }
    }

    return false;
  }

  /**
   * Removes the trailing brackets with its content for a JUnit4 test name. Examples: test_name[0]
   * -> test_name [some]test_name -> [some]test_name
   *
   * @param testName
   * @return testName normalized
   */
  public static String normalizeTestName(final String testName) {
    if (testName == null || testName.isEmpty()) {
      return testName;
    }

    // We could use a simple .endsWith function for pure JUnit4 tests,
    // however, we need to use a regex to find the trailing brackets specifically because if the
    // test is based on Spock v1 (that runs JUnit4 listeners under the hood)
    // there are no test name restrictions (it can be any string).
    return testNameNormalizerRegex.matcher(testName).replaceAll("");
  }
}
