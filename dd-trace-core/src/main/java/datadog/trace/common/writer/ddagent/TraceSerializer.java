package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.MsgpackFormatWriter.MSGPACK_WRITER;

import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import lombok.extern.slf4j.Slf4j;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TraceSerializer implements AutoCloseable, Runnable {

  private static final Field PACKER_POSITION;

  static {
    try {
      Field field = MessagePacker.class.getDeclaredField("position");
      field.setAccessible(true);
      PACKER_POSITION = field;
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException("Can't access MessagePacker.position");
    }
  }

  private volatile boolean running = true;

  private final DispatchingMessageBufferOutput buffer;
  private final MessagePacker packer;
  private final ArrayBlockingQueue<List<DDSpan>> queue;
  private final Monitor monitor;
  private final DDAgentWriter ddAgentWriter;
  private final TraceProcessor traceProcessor;
  private final long flushDurationMillis;
  private final int flushBytes;

  private long nextFlushMillis;

  public TraceSerializer(final DDAgentApi ddAgentApi,
                         Monitor monitor,
                         ArrayBlockingQueue<List<DDSpan>> queue,
                         DDAgentWriter ddAgentWriter,
                         TraceProcessor traceProcessor, long flushFrequency,
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
    this.traceProcessor = traceProcessor;
    this.packer = MessagePack.newDefaultPacker(buffer);
    this.flushDurationMillis = timeUnit.toMillis(flushFrequency);
    this.nextFlushMillis = flushFrequency == -1L ? Long.MAX_VALUE : nowMillis() + flushDurationMillis;
    this.flushBytes = 1 << 20;
  }

  public void notifyUnreportedTraces(int howMany) {
    notifyDropped(howMany);
  }

  public void notifyDropped(int howMany) {
    buffer.onDroppedTrace(howMany);
  }

  @Override
  public void run() {
    List<List<DDSpan>> traces = new ArrayList<>(1024);
    while (running) {
      try {
        List<DDSpan> first = queue.take();
        traces.add(first);
        queue.drainTo(traces, 1023);
        for (List<DDSpan> trace : traces) {
          try {
            MSGPACK_WRITER.writeTrace(traceProcessor.onTraceComplete(trace), packer);
            buffer.onTraceWritten();
          } catch (final Throwable e) {
            if (log.isDebugEnabled()) {
              log.debug("Error while serializing trace", e);
            }
            monitor.onFailedSerialize(ddAgentWriter, trace, e);
          }
        }
        flushPacker(false);
        traces.clear();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
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
    if (force || now >= nextFlushMillis
      || getMessagePackerPosition() >= flushBytes) {
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

  private int getMessagePackerPosition() {
    try {
      return (int) PACKER_POSITION.get(packer);
    } catch (IllegalAccessException e) {
      return 0;
    }
  }
}
