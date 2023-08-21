package datadog.trace.api.civisibility;

import javax.annotation.Nullable;

public class CIVisibility {

  private static volatile SessionFactory SESSION_FACTORY =
      (projectName, component, startTime) -> {
        throw new UnsupportedOperationException(
            "session factory not registered, " + "please ensure CI Visibility feature is enabled");
      };

  /**
   * This a hook for injecting SessionFactory implementation. It should only be used internally by
   * the tracer logic
   *
   * @param sessionFactory Session factory instance
   */
  public static void registerSessionFactory(SessionFactory sessionFactory) {
    SESSION_FACTORY = sessionFactory;
  }

  /**
   * Marks the start of a new test session.
   *
   * @param projectName The name of the tested project
   * @param component The name of the test component (typically corresponds to the name of the
   *     testing framework used). This will be added as prefix to reported span names
   * @param startTime Optional start time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   * @return Handle to the test session instance
   */
  public static DDTestSession startSession(
      String projectName, String component, @Nullable Long startTime) {
    return SESSION_FACTORY.startSession(projectName, component, startTime);
  }

  public interface SessionFactory {
    DDTestSession startSession(String projectName, String component, Long startTime);
  }
}
