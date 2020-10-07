package datadog.trace.common.writer;

import datadog.trace.core.DDSpan;
import java.io.Closeable;
import java.util.List;

/** A writer is responsible to send collected spans to some place */
public interface Writer extends Closeable {

  /**
   * Write a trace represented by the entire list of all the finished spans
   *
   * @param trace the list of spans to write
   */
  void write(List<DDSpan> trace);

  /** Start the writer */
  void start();

  /**
   * Requests the writer to send all finished traces and block until complete.
   *
   * @return true if completed normally
   */
  boolean flush();

  /**
   * Verifies all associated threads are still active and the writer is accepting traces
   *
   * @throws AssertionError if anything is not running (and assertions are enabled)
   */
  void assertRunning() throws AssertionError;

  /**
   * Indicates to the writer that no future writing will come and it should terminates all
   * connections and tasks
   */
  @Override
  void close();

  /** Count that a trace was captured for stats, but without reporting it. */
  void incrementTraceCount();
}
