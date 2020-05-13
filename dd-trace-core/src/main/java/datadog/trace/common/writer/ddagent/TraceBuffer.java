package datadog.trace.common.writer.ddagent;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/**
 * The purpose of this abtraction is to decouple serialization from preparation of a request, to
 * allow incremental refactoring towards building requests into a small number of TraceBuffers
 * pooled in what is now the BatchWritingDisruptor. It independently tracks how many traces have
 * been written into it and how many bytes have been written, so that it can simply be translated to
 * an HTTP POST to the agent, before being returned to the disruptor in a single read transaction.
 */
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
