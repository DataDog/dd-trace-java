package datadog.trace.codecoverage;

import java.util.BitSet;
import java.util.Map;

/**
 * Interface for sending collected code coverage data to a backend. Phase 1 uses a logging stub;
 * future phases will implement real sending to Datadog.
 */
public interface CodeCoverageSender {

  /**
   * Sends coverage data.
   *
   * @param coverage map from source file path (e.g. {@code "com/example/MyClass.java"}) to set of
   *     covered line numbers
   */
  void send(Map<String, BitSet> coverage);
}
