package datadog.trace.profiling;

import java.io.Closeable;

/** Represents the current profiling session Call close method to end the profiling session */
public interface Session extends Closeable {

  /** Ends the current profiling session */
  void close();

  /** @return profiling information into a serialized form */
  byte[] getData();
}
