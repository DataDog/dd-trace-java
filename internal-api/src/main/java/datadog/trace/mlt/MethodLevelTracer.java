package datadog.trace.mlt;

import lombok.extern.slf4j.Slf4j;

/**
 * Entry point of the method-level tracing API to allow trigger sampling profiling on demand
 *
 * <p>Example of usage:
 *
 * <pre>
 *   try (Session session = MethodLevelTracer.startProfiling(traceId)) {
 *     // ...
 *   }
 * </pre>
 *
 * or
 *
 * <pre>
 *   Session session = MethodLevelTracer.startProfiling(traceId)
 *   // ... and into another method:
 *   session.close();
 * </pre>
 */
@Slf4j
public class MethodLevelTracer {
  private static volatile SessionFactory factory;
  private static final Session NO_SESSION = new NoSession();

  /**
   * Starts a profiling session for the current thread
   *
   * @return an instance of profiling session
   */
  public static Session startProfiling(final String id) {
    return startProfiling(id, Thread.currentThread());
  }

  /**
   * Starts a profiling session for the specified thread
   *
   * @return an instance of profiling session
   */
  public static Session startProfiling(final String id, final Thread thread) {
    final SessionFactory localFactory = factory;
    if (localFactory == null) {
      log.warn("Profiling session not initialized");
      return NO_SESSION;
    }
    return localFactory.createSession(id, thread);
  }

  /**
   * Initializes the method-level tracing API with an implementation through SessionFactory
   *
   * @param sessionFactory
   */
  public static void initialize(final SessionFactory sessionFactory) {
    factory = sessionFactory;
  }

  public static void shutdown() {
    SessionFactory localFactory = factory;
    if (localFactory != null) {
      factory.shutdown();
    }
  }

  private static class NoSession implements Session {
    @Override
    public byte[] close() {
      return null;
    }
  }
}
