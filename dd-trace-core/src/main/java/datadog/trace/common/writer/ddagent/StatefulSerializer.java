package datadog.trace.common.writer.ddagent;

import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.util.List;

public interface StatefulSerializer {

  /**
   * Serialises the trace into a trace buffer.
   *
   * @param trace a list of spans making up a trace
   * @return how many bytes were serialized
   * @throws IOException
   */
  int serialize(List<DDSpan> trace) throws IOException;

  void dropBuffer() throws IOException;

  /**
   * returns a newly allocated buffer
   *
   * @return a new buffer
   */
  TraceBuffer newBuffer();

  /**
   * Returns true if the current buffer is near or exceeding capacity. This is advice to claim the
   * buffer and reset.
   *
   * @return true if the buffer should be reset
   */
  boolean shouldFlush();

  /**
   * Resets the buffer to use
   *
   * @param buffer
   */
  void reset(TraceBuffer buffer);
}
