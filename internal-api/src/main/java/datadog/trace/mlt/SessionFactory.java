package datadog.trace.mlt;

/** Factory of profiling session */
public interface SessionFactory {
  /**
   * Creates a profiling session for the specified thread.
   *
   * @return an instance of the profiling session
   */
  Session createSession(String id, Thread thread);

  /** Shutdowns some resources associated with the SessionFactory */
  void shutdown();
}
