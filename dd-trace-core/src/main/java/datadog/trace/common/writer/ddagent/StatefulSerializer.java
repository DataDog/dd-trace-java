package datadog.trace.common.writer.ddagent;

import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.util.List;

public interface StatefulSerializer {

  /**
   * Serialises the trace into a trace buffer.
   *
   * @param trace a list of spans making up a trace
   * @throws IOException
   */
  void serialize(List<DDSpan> trace) throws IOException;

  /**
   * Returns a buffer containing all traces written since the last call to this method. The buffer
   * belongs to the caller and should no longer be referenced by the serializer after being
   * released.
   *
   * @return the buffer into which traces have been serialized.
   * @throws IOException
   */
  TraceBuffer getBuffer() throws IOException;
}
