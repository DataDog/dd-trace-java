package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.MsgpackFormatWriter.MSGPACK_WRITER;
import static org.msgpack.core.MessagePack.Code.ARRAY16;
import static org.msgpack.core.MessagePack.Code.ARRAY32;
import static org.msgpack.core.MessagePack.Code.FIXARRAY_PREFIX;

import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.buffer.ArrayBufferOutput;
import org.msgpack.core.buffer.MessageBuffer;

/**
 * Serialises traces into a buffer and on demand releases the buffer. Aims to dynamically size
 * buffers based on the recent history of trace sizes and how many traces were recently written into
 * a single buffer.
 *
 * <p>Intentionally not thread-safe; each thread should have a dedicated instance in multi-threaded
 * settings.
 */
@Slf4j
public class MsgPackStatefulSerializer implements StatefulSerializer {

  public static final int DEFAULT_BUFFER_THRESHOLD = 1 << 20; // 1MB

  // assumed to be a power of 2 for arithmetic efficiency
  private static final int TRACE_HISTORY_SIZE = 16;
  private static final int INITIAL_TRACE_SIZE_ESTIMATE = 8 * 1024; // 8KB

  // limiting the size this optimisation applies to decreases the likelihood
  // that the MessagePacker will allocate a byte[] during UTF-8 encoding,
  // and restricts the optimisation to very small strings which may scalarise
  private static final MessagePack.PackerConfig MESSAGE_PACKER_CONFIG =
      MessagePack.DEFAULT_PACKER_CONFIG.withSmallStringOptimizationThreshold(16);

  // reusing this within the context of each thread is handy because it
  // caches an Encoder
  private final MessagePacker messagePacker;

  private final int[] traceSizeHistory = new int[TRACE_HISTORY_SIZE];
  private final int sizeThresholdBytes;
  private final int bufferSize;

  private int runningTraceSizeSum;
  private int position;
  private MsgPackTraceBuffer traceBuffer;

  private int currentSerializedBytes = 0;

  public MsgPackStatefulSerializer() {
    this(DEFAULT_BUFFER_THRESHOLD, DEFAULT_BUFFER_THRESHOLD * 3 / 2); // 1MB
  }

  public MsgPackStatefulSerializer(int sizeThresholdBytes, int bufferSize) {
    Arrays.fill(traceSizeHistory, INITIAL_TRACE_SIZE_ESTIMATE);
    this.runningTraceSizeSum = INITIAL_TRACE_SIZE_ESTIMATE * TRACE_HISTORY_SIZE;
    this.sizeThresholdBytes = sizeThresholdBytes;
    this.bufferSize = bufferSize;
    this.messagePacker = MESSAGE_PACKER_CONFIG.newPacker(new ArrayBufferOutput(0));
  }

  @Override
  public int serialize(List<DDSpan> trace) throws IOException {
    MSGPACK_WRITER.writeTrace(trace, messagePacker);
    int newSerializedSize = (int) messagePacker.getTotalWrittenBytes();
    int serializedSize = newSerializedSize - currentSerializedBytes;
    currentSerializedBytes = newSerializedSize;
    updateTraceSizeEstimate(serializedSize);
    ++traceBuffer.traceCount;
    traceBuffer.length = newSerializedSize;
    return serializedSize;
  }

  @Override
  public void dropBuffer() throws IOException {
    messagePacker.flush();
    traceBuffer = null;
  }

  @Override
  public boolean isAtCapacity() {
    // Return true if could not take another average trace without allocating.
    // There are many cases where this will lead to some amount of over allocation,
    // e.g. a very large trace after many very small traces, but it's a best effort
    // strategy to avoid buffer growth eventually.
    return currentSerializedBytes + avgTraceSize() >= sizeThresholdBytes;
  }

  @Override
  public void reset(TraceBuffer buffer) {
    if (buffer instanceof MsgPackTraceBuffer) {
      this.traceBuffer = (MsgPackTraceBuffer) buffer;
      this.traceBuffer.reset();
    } else { // i.e. if (null == buffer || unuseable)
      this.traceBuffer = newBuffer();
    }
    // reset the packer's position to zero
    messagePacker.clear();
    try {
      messagePacker.reset(traceBuffer.buffer);
    } catch (IOException e) { // don't expect this to happen
      log.error("Unexpected exception resetting MessagePacker buffer", e);
    }
  }

  @Override
  public MsgPackTraceBuffer newBuffer() {
    return new MsgPackTraceBuffer(new ArrayBufferOutput(bufferSize));
  }

  private void updateTraceSizeEstimate(int traceSize) {
    // This is a moving average calculation based on the last
    // TRACE_HISTORY_SIZE trace sizes, stored in a ring buffer.
    // A ring buffer of recent trace sizes is maintained. On each
    // update, the value at position is subtracted from the running
    // sum, and the new value is added. The value at position is
    // replaced by the new value. The position is incremented modulo
    // TRACE_HISTORY_SIZE. TRACE_HISTORY_SIZE has been chosen as a
    // power of two to keep this calculation as cheap as possible.
    //
    // The trace history is initialised to INITIAL_TRACE_SIZE_ESTIMATE to
    // simplify the calculation during the initial filling of the history,
    // and to bias early estimates toward small trace sizes.
    runningTraceSizeSum = (runningTraceSizeSum - traceSizeHistory[position] + traceSize);
    traceSizeHistory[position] = traceSize;
    position = (position + 1) & (traceSizeHistory.length - 1);
  }

  private int avgTraceSize() {
    return runningTraceSizeSum / TRACE_HISTORY_SIZE;
  }

  static class MsgPackTraceBuffer implements TraceBuffer {

    private static final AtomicInteger BUFFER_ID = new AtomicInteger(0);

    private final ArrayBufferOutput buffer;
    final int id;
    private int length;
    private int traceCount;
    private int representativeCount;
    private Runnable flush;

    public MsgPackTraceBuffer(ArrayBufferOutput buffer) {
      this.buffer = buffer;
      this.id = BUFFER_ID.getAndIncrement();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      writeHeader(channel);
      int remaining = length;
      for (MessageBuffer messageBuffer : buffer.toBufferList()) {
        int size = messageBuffer.size();
        ByteBuffer buffer = messageBuffer.sliceAsByteBuffer(0, Math.min(size, remaining));
        while (buffer.hasRemaining()) {
          remaining -= channel.write(buffer);
        }
      }
      assert remaining == 0;
    }

    private void writeHeader(WritableByteChannel channel) throws IOException {
      // inlines behaviour from MessagePacker.packArrayHeader
      if (traceCount < (1 << 4)) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put(0, (byte) (traceCount | FIXARRAY_PREFIX));
        channel.write(buffer);
      } else if (traceCount < (1 << 16)) {
        ByteBuffer buffer = ByteBuffer.allocate(3);
        buffer.put(0, ARRAY16);
        buffer.putShort(1, (short) traceCount);
        channel.write(buffer);
      } else {
        ByteBuffer buffer = ByteBuffer.allocate(5);
        buffer.put(0, ARRAY32);
        buffer.putInt(1, traceCount);
        channel.write(buffer);
      }
    }

    @Override
    public int sizeInBytes() {
      return length;
    }

    @Override
    public int headerSize() {
      // Need to allocate additional to handle MessagePacker.packArrayHeader
      if (traceCount < (1 << 4)) {
        return 1;
      } else if (traceCount < (1 << 16)) {
        return 3;
      } else {
        return 5;
      }
    }

    @Override
    public int traceCount() {
      return traceCount;
    }

    @Override
    public int representativeCount() {
      return representativeCount;
    }

    @Override
    public void setRepresentativeCount(int representativeCount) {
      this.representativeCount = representativeCount;
    }

    @Override
    public int id() {
      return id;
    }

    @Override
    public void setDispatchRunnable(Runnable flush) {
      this.flush = flush;
    }

    @Override
    public void onDispatched() {
      if (null != flush) {
        flush.run();
        flush = null;
      }
    }

    public void reset() {
      buffer.clear();
      traceCount = 0;
      length = 0;
      representativeCount = 0;
    }
  }
}
