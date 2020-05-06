package datadog.trace.profiling;

import java.io.Closeable;

/**
 * Represents the current profiling session
 * Call close method to end the profiling session
 */
public interface Session extends Closeable {

  /**
   * Adds a thread to be sampled for the current session
   * @param thread additional thread to be sampled
   */
  void addThread(Thread thread);

  /**
   * Ends the current profiling session
   */
  void close();
}
