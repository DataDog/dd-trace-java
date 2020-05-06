package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.MsgpackFormatWriter.MSGPACK_WRITER;

import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.core.DDSpan;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MpscCompoundQueue;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TraceProcessor implements AutoCloseable, Runnable {

  private volatile boolean running = true;

  private final DispatchingMessageBufferOutput buffer;
  private final MessagePacker packer;
  private final MpscCompoundQueue<List<DDSpan>> queue;
  private final Monitor monitor;
  private final DDAgentWriter ddAgentWriter;
  private final long flushDurationMillis;

  private long nextFlushMillis;

  public TraceProcessor(final DDAgentApi ddAgentApi,
                        Monitor monitor,
                        MpscCompoundQueue<List<DDSpan>> queue,
                        DDAgentWriter ddAgentWriter,
                        long flushFrequency,
                        TimeUnit timeUnit) {
    this.buffer = new DispatchingMessageBufferOutput(new DispatchingMessageBufferOutput.Output() {
      @Override
      public void accept(int traceCount, int representativeCount, ByteBuffer buffer) {
        ddAgentApi.sendTraces(traceCount, representativeCount, buffer);
      }
    });
    this.monitor = monitor;
    this.queue = queue;
    this.ddAgentWriter = ddAgentWriter;
    this.packer = MessagePack.newDefaultPacker(buffer);
    this.flushDurationMillis = timeUnit.toMillis(flushFrequency);
    this.nextFlushMillis = flushFrequency == -1L ? Long.MAX_VALUE : nowMillis() + flushDurationMillis;
  }

  public void notifyUnreportedTraces(int howMany) {
    notifyDropped(howMany);
  }

  public void notifyDropped(int howMany) {
    buffer.onDroppedTrace(howMany);
  }

  @Override
  public void run() {
    while (running) {
      List<DDSpan> trace = queue.poll();
      if (null != trace) {
        try {
          MSGPACK_WRITER.writeTrace(trace, packer);
          buffer.onTraceWritten();
          flushPacker(false);
        } catch (final Throwable e) {
          if (log.isDebugEnabled()) {
            log.debug("Error while serializing trace", e);
          }
          monitor.onFailedSerialize(ddAgentWriter, trace, e);
        }
      }
    }
    flushPacker(true);
  }

  @Override
  public void close() {
    running = false;
  }

  private void flushPacker(boolean force) {
    long now = nowMillis();
    if (now >= nextFlushMillis || force) {
      nextFlushMillis = now + flushDurationMillis;
      try {
        packer.flush();
      } catch (Exception e) {
        log.error("Error flushing message pack data");
      }
    }
  }

  private static long nowMillis() {
    // note that nanoTime is used in favour to currentTimeMillis because it is monotonic
    return System.nanoTime() / 1_000_000;
  }
}
