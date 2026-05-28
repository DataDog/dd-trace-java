package datadog.trace.api.civisibility;

import datadog.trace.api.civisibility.noop.NoOpDDTestSession;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

public class CIVisibility {

  private static volatile SessionFactory SESSION_FACTORY =
      (projectName, projectRoot, component, startTime) -> NoOpDDTestSession.INSTANCE;

  private static final Map<String, CIVisibilityEvent> ACTIVE_TEST_SESSIONS =
      new ConcurrentHashMap<>();
  private static final Map<String, CIVisibilityEvent> ACTIVE_TEST_MODULES =
      new ConcurrentHashMap<>();

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
   * Hook for the tracer to expose an active test session keyed by component. Only used in headless
   * mode (one session per component).
   */
  public static void registerActiveTestSession(String component, CIVisibilityEvent session) {
    ACTIVE_TEST_SESSIONS.put(component, session);
  }

  /** Removes a previously registered active test session. */
  public static void unregisterActiveTestSession(String component, CIVisibilityEvent session) {
    ACTIVE_TEST_SESSIONS.remove(component, session);
  }

  /**
   * Hook for the tracer to expose an active test module keyed by component. Only used in headless
   * mode (one module per component).
   */
  public static void registerActiveTestModule(String component, CIVisibilityEvent module) {
    ACTIVE_TEST_MODULES.put(component, module);
  }

  /** Removes a previously registered active test module. */
  public static void unregisterActiveTestModule(String component, CIVisibilityEvent module) {
    ACTIVE_TEST_MODULES.remove(component, module);
  }

  /**
   * Returns an unmodifiable view of the currently active headless test sessions, keyed by
   * component.
   */
  public static Map<String, CIVisibilityEvent> activeTestSessions() {
    return Collections.unmodifiableMap(ACTIVE_TEST_SESSIONS);
  }

  /**
   * Returns an unmodifiable view of the currently active headless test modules, keyed by component.
   */
  public static Map<String, CIVisibilityEvent> activeTestModules() {
    return Collections.unmodifiableMap(ACTIVE_TEST_MODULES);
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
