package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;
import java.io.Writer;
import java.util.function.LongConsumer;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * The buffering half of RUM injection for char streams: a circular lookbehind buffer plus draining,
 * committing, char counting and the actual content injection. It knows nothing about <em>where</em>
 * to inject; a {@link CharMatcher} decides that by driving these operations.
 *
 * <p>In case of IOException thrown by the downstream, the buffer will be lost unless the error
 * occurred when draining it. In this case the draining will be resumed.
 */
@NotThreadSafe
public final class InjectingCharBuffer {
  private final char[] lookbehind;
  private int pos;
  private int count;
  private final char[] contentToInject;
  private boolean filter;
  private boolean wasDraining;
  private final Runnable onContentInjected;
  private final Writer downstream;
  private final LongConsumer onBytesWritten;
  private final LongConsumer onInjectionTime;
  private long bytesWritten = 0;

  public InjectingCharBuffer(
      final Writer downstream,
      final char[] contentToInject,
      final int lookbehindSize,
      final Runnable onContentInjected,
      final LongConsumer onBytesWritten,
      final LongConsumer onInjectionTime) {
    this.downstream = downstream;
    this.contentToInject = contentToInject;
    this.lookbehind = new char[lookbehindSize];
    this.pos = 0;
    this.count = 0;
    this.wasDraining = false;
    // should filter the stream to potentially inject into it.
    this.filter = true;
    this.onContentInjected = onContentInjected;
    this.onBytesWritten = onBytesWritten;
    this.onInjectionTime = onInjectionTime;
  }

  /**
   * @return whether the stream is still being filtered (i.e. injection has not happened yet).
   */
  public boolean isFiltering() {
    return filter;
  }

  public void setFilter(boolean filter) {
    this.filter = filter;
  }

  /** Writes a unit straight through when not filtering, resuming an interrupted drain first. */
  public void passthrough(int c) throws IOException {
    if (wasDraining) {
      drain();
    }
    downstream.write(c);
    bytesWritten++;
  }

  /** Writes a range straight through when not filtering, resuming an interrupted drain first. */
  public void passthrough(char[] array, int off, int len) throws IOException {
    if (wasDraining) {
      drain();
    }
    downstream.write(array, off, len);
    bytesWritten += len;
  }

  /** Emits a single unit downstream (used while filtering). */
  public void emit(int c) throws IOException {
    downstream.write(c);
    bytesWritten++;
  }

  /** Writes a range of the source array straight downstream, counting it as original chars. */
  public void writeThrough(char[] array, int off, int len) throws IOException {
    downstream.write(array, off, len);
    bytesWritten += len;
  }

  /** Holds a unit in the lookbehind buffer, spilling the oldest downstream if it is full. */
  public void hold(int c) throws IOException {
    if (count == lookbehind.length) {
      downstream.write(lookbehind[pos]);
      bytesWritten++;
    } else {
      count++;
    }
    lookbehind[pos] = (char) c;
    pos = (pos + 1) % lookbehind.length;
  }

  /** Writes the injected content downstream and fires the injection callbacks. */
  public void writeContent() throws IOException {
    long injectionStart = System.nanoTime();
    downstream.write(contentToInject);
    long injectionEnd = System.nanoTime();
    if (onInjectionTime != null) {
      onInjectionTime.accept((injectionEnd - injectionStart) / 1_000_000L);
    }
    if (onContentInjected != null) {
      onContentInjected.run();
    }
  }

  public void drain() throws IOException {
    if (count > 0) {
      boolean wasFiltering = filter;
      filter = false;
      wasDraining = true;
      int start = (pos - count + lookbehind.length) % lookbehind.length;
      int cnt = count;
      for (int i = 0; i < cnt; i++) {
        downstream.write(lookbehind[(start + i) % lookbehind.length]);
        bytesWritten++;
        count--;
      }
      filter = wasFiltering;
      wasDraining = false;
    }
  }

  public void commit() throws IOException {
    if (filter || wasDraining) {
      drain();
    }
  }

  public void flush() throws IOException {
    downstream.flush();
  }

  public void close() throws IOException {
    try {
      commit();
    } finally {
      downstream.close();
      // report the size of the original HTTP response before injecting via callback
      if (onBytesWritten != null) {
        onBytesWritten.accept(bytesWritten);
      }
      bytesWritten = 0;
    }
  }
}
