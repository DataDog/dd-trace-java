package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.MsgpackFormatWriter.MSGPACK_WRITER;

import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;
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

  // assumed to be a power of 2 for arithmetic efficiency
  private static final int TRACE_HISTORY_SIZE = 16;
  private static final int INITIAL_BUFFER_SIZE = 1024;

  // limiting the size this optimisation applies to decreases the likelihood
  // that the MessagePacker will allocate a byte[] during UTF-8 encoding,
  // and restricts the optimisation to very small strings which may scalarise
  private static final MessagePack.PackerConfig MESSAGE_PACKER_CONFIG =
      MessagePack.DEFAULT_PACKER_CONFIG.withSmallStringOptimizationThreshold(16);

  // reusing this within the context of each thread is handy because it
  // caches an Encoder
  private final MessagePacker messagePacker =
      MESSAGE_PACKER_CONFIG.newPacker(new ArrayBufferOutput(0));

  private final int[] traceSizes = new int[TRACE_HISTORY_SIZE];

  private int traceSizeSum;
  private int position;
  private MsgPackTraceBuffer lastBuffer;

  private int lastTracesPerBuffer = 1;
  private int tracesPerBuffer;

  public MsgPackStatefulSerializer() {
    Arrays.fill(traceSizes, INITIAL_BUFFER_SIZE);
    this.traceSizeSum = INITIAL_BUFFER_SIZE * TRACE_HISTORY_SIZE;
  }

  @Override
  public void serialize(List<DDSpan> trace) throws IOException {
    if (null == lastBuffer) {
      lastBuffer = newBuffer();
      messagePacker.reset(lastBuffer.buffer);
      messagePacker.clear();
    }
    MSGPACK_WRITER.writeTrace(trace, messagePacker);
    int serializedSize = (int) messagePacker.getTotalWrittenBytes();
    updateTraceSize(serializedSize);
    lastBuffer.length += serializedSize;
    ++lastBuffer.traceCount;
    ++tracesPerBuffer;
  }

  @Override
  public TraceBuffer getBuffer() throws IOException {
    try {
      messagePacker.flush();
      return lastBuffer;
    } finally {
      lastTracesPerBuffer = tracesPerBuffer;
      tracesPerBuffer = 0;
      lastBuffer = null;
    }
  }

  private MsgPackTraceBuffer newBuffer() {
    MsgPackTraceBuffer buffer = new MsgPackTraceBuffer();
    int traceBufferSize = traceBufferSize();
    buffer.buffer = new ArrayBufferOutput(traceBufferSize);
    return buffer;
  }

  private void updateTraceSize(int traceSize) {
    traceSizeSum = (traceSizeSum - traceSizes[position] + traceSize);
    traceSizes[position] = traceSize;
    position = (position + 1) & (traceSizes.length - 1);
  }

  private int traceBufferSize() {
    // round up to next KB, assumes for now that there will be one trace per buffer
    return ((lastTracesPerBuffer * traceSizeSum / TRACE_HISTORY_SIZE) + 1023) / 1024;
  }

  public static class MsgPackTraceBuffer implements TraceBuffer {

    private ArrayBufferOutput buffer;
    private int length;
    private int traceCount;

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      int remaining = length;
      for (MessageBuffer messageBuffer : buffer.toBufferList()) {
        int size = messageBuffer.size();
        ByteBuffer buffer =
            size > remaining
                ? messageBuffer.sliceAsByteBuffer(0, remaining)
                : messageBuffer.sliceAsByteBuffer();
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        remaining -= size;
      }
      assert remaining == 0;
    }

    @Override
    public int sizeInBytes() {
      return length;
    }

    @Override
    public int traceCount() {
      return traceCount;
    }
  }
}
