package datadog.trace.core.datastreams.extractors;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.WritableFormatter;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class TransactionExtractor {
  protected TransactionHolder holder;

  public TransactionExtractor(TimeSource timeSource, String name, String value) {
    this.holder = new TransactionHolder(timeSource, UTF8BytesString.create(name), value);
  }

  public <C> void fromContext(C carrier, AgentPropagation.ContextVisitor<C> contextVisitor) {
    contextVisitor.forEachKeyValue(carrier, this.holder);
  }

  public void flushTo(WritableFormatter writer) {
    this.holder.flushTo(writer);
  }

  protected static class TransactionHolder implements BiConsumer<String, String> {
    private final UTF8BytesString name;
    private final String key;
    private final TimeSource timeSource;
    private final ConcurrentLinkedQueue<TransactionInfo> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();

    public TransactionHolder(TimeSource timeSource, UTF8BytesString name, String key) {
      this.key = key;
      this.timeSource = timeSource;
      this.name = name;
    }

    @Override
    public void accept(String key, String value) {
      if (key.equals(this.key)) {
        queue.add(new TransactionInfo(value, timeSource.getCurrentTimeNanos()));
        size.incrementAndGet();
      }
    }

    public void flushTo(WritableFormatter writer) {
      // we don't care for the exact size, approximate is fine
      int maxSize = size.getAndSet(0);

      // get list of items to flush
      List<TransactionInfo> toFlush = new ArrayList<>(maxSize);
      for (int i = 0; i < maxSize; i++) {
        TransactionInfo info = queue.poll();
        if (info == null) {
          break;
        }
        toFlush.add(info);
      }

      // flush
      writer.startArray(toFlush.size());
      for (TransactionInfo info : toFlush) {
        writer.startArray(2);
        writer.writeLong(info.getTimestamp());
        writer.writeUTF8(name);
        writer.writeUTF8(info.getId().getBytes(UTF_8));
      }
    }
  }
}
