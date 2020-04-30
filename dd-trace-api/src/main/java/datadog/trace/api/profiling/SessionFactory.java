package datadog.trace.api.profiling;

/**
 * Factory of profiling session
 */
public interface SessionFactory {
  /**
   * Creates a profiling session. if this is a nested calls, returns the current started Session
   * @return an instance of the profiling session
   */
  Session createSession();
}
