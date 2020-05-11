package datadog.trace.common.writer.ddagent;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

public interface TraceBuffer {

  /**
   * The size in bytes of the data in the buffer, may be less than or equal to the total size of the
   * buffer.
   *
   * @return
   */
  int sizeInBytes();

  /**
   * The number of traces held in the buffer
   *
   * @return the number of traces in the buffer.
   */
  int traceCount();

  /**
   * Writes the pooled buffer to the channel and consumes the buffer. Can only be called once; after
   * calling the method an attempt will be made to put the buffer back in the pool.
   *
   * @param channel
   * @throws IOException
   */
  void writeTo(WritableByteChannel channel) throws IOException;
}
