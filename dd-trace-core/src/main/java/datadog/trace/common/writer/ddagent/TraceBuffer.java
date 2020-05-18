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
   * @return the size in bytes
   */
  int sizeInBytes();

  /**
   * The size of any metadata which will be written
   *
   * @return
   */
  int headerSize();

  /**
   * The number of traces held in the buffer
   *
   * @return the number of traces in the buffer.
   */
  int traceCount();

  /**
   * The number of traces during the period this buffer was being written to, including those which
   * were dropped.
   *
   * @return the representative count of traces
   */
  int representativeCount();

  /**
   * Tag the buffer with the representative count of all traces which weren't dropped while it was
   * being built.
   */
  void setRepresentativeCount(int representativeCount);

  /**
   * The identity
   *
   * @return
   */
  int id();

  /**
   * Set a runnable to invoke when the event has been used.
   *
   * @param runnable
   */
  void setDispatchRunnable(Runnable runnable);

  /**
   * To be called when the buffer has been used, for the purposes of flushing. Doesn't have to do
   * anything.
   */
  void onDispatched();

  /**
   * Writes the pooled buffer to the channel and consumes the buffer. Can only be called once; after
   * calling the method an attempt will be made to put the buffer back in the pool.
   *
   * @param channel
   * @throws IOException
   */
  void writeTo(WritableByteChannel channel) throws IOException;
}
