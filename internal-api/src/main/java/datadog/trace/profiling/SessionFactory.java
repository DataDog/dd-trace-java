package datadog.trace.profiling;

/**
 * Factory of profiling session
 */
public interface SessionFactory {
  /**
   * Creates a profiling session for the specified thread. If this is a nested calls,
   * returns the current started Session
   * @return an instance of the profiling session
   */
  Session createSession(Thread thread);
}
