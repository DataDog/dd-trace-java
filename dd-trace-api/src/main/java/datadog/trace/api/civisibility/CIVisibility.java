package datadog.trace.api.civisibility;

import datadog.trace.api.civisibility.noop.NoOpDDTestSession;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

public class CIVisibility {

  private static volatile SessionFactory SESSION_FACTORY =
      (projectName, projectRoot, component, startTime) -> NoOpDDTestSession.INSTANCE;

  @Nullable private static volatile CIVisibilityEvent ACTIVE_TEST_SESSION = null;
  @Nullable private static volatile CIVisibilityEvent ACTIVE_TEST_MODULE = null;

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
   * Hook for the tracer to expose the currently active test session. Pass {@code null} when the
   * session ends.
   */
  public static void registerActiveTestSession(@Nullable CIVisibilityEvent session) {
    ACTIVE_TEST_SESSION = session;
  }

  /**
   * Hook for the tracer to expose the currently active test module. Pass {@code null} when the
   * module ends.
   */
  public static void registerActiveTestModule(@Nullable CIVisibilityEvent module) {
    ACTIVE_TEST_MODULE = module;
  }

  /**
   * Returns a handle to the currently active test session (limited to headless and manual API) so
   * users can attach custom tags, or {@code null} when no test session is being managed by the
   * tracer.
   */
  @Nullable
  public static CIVisibilityEvent activeTestSession() {
    return ACTIVE_TEST_SESSION;
  }

  /**
   * Returns a handle to the currently active test module (limited to headless and manual API) so
   * users can attach custom tags, or {@code null} when no test module is being managed by the
   * tracer.
   */
  @Nullable
  public static CIVisibilityEvent activeTestModule() {
    return ACTIVE_TEST_MODULE;
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
    Path projectRoot = Paths.get("").toAbsolutePath();
    return SESSION_FACTORY.startSession(projectName, projectRoot, component, startTime);
  }

  public static DDTestSession startSession(
      String projectName, Path projectRoot, String component, @Nullable Long startTime) {
    return SESSION_FACTORY.startSession(projectName, projectRoot, component, startTime);
  }

  public interface SessionFactory {
    DDTestSession startSession(
        String projectName, Path projectRoot, String component, Long startTime);
  }
}
