package datadog.trace.api.profiling;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point of the Profiling API to allow trigger sampling profiling on demand
 * Example of usage:
 * <pre>
 *   try (Session session = Profiler.startProfiling()) {
 *     // ...
 *   }
 * </pre>
 * or
 * <pre>
 *   Session session = Profiler.startProfiling()
 *   // ... and into another method:
 *   session.close();
 * </pre>
 *
 * Nested calls are allowed but only outer calls will be effective
 */
@Slf4j
public class Profiler {
  private static volatile SessionFactory factory;
  private static final Session NO_SESSION = new NoSession();

  /**
   * Starts a profiling session
   * @return an instance of profiling session
   */
  public static Session startProfiling() {
    SessionFactory localFactory = factory;
    if (localFactory == null) {
      log.warn("Profiling session not initialized");
      return NO_SESSION;
    }
    return localFactory.createSession();
  }

  /**
   * Initializes the Profiler API with an implementation through SessionFactory
   * @param sessionFactory
   */
  public static void initialize(SessionFactory sessionFactory) {
    factory = sessionFactory;
  }

  private static class NoSession implements Session {
    @Override
    public void close() {
    }
  }
}
