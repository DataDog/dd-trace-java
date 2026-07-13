package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.LongConsumer;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * An OutputStream that injects content once, at a position chosen by a {@link ByteMatcher}. It is a
 * thin composition of the buffering ({@link InjectingByteBuffer}) and the matching (the {@link
 * ByteMatcher}); all the work lives in those two collaborators.
 */
@NotThreadSafe
public class InjectingPipeOutputStream extends OutputStream {
  private final InjectingByteBuffer buffer;
  private final ByteMatcher matcher;

  /**
   * Convenience constructor using literal marker matching (typically used for testing).
   *
   * @param downstream the delegate output stream
   * @param marker the marker to find in the stream. Must at least be one byte.
   * @param contentToInject the content to inject once before the marker if found.
   */
  public InjectingPipeOutputStream(
      final OutputStream downstream, final byte[] marker, final byte[] contentToInject) {
    this(downstream, contentToInject, new LiteralByteMatcher(marker), null, null, null);
  }

  /**
   * Convenience constructor using literal marker matching with the full set of callbacks.
   *
   * @param downstream the delegate output stream
   * @param marker the marker to find in the stream. Must at least be one byte.
   * @param contentToInject the content to inject once before the marker if found.
   * @param onContentInjected callback called when and if the content is injected.
   * @param onBytesWritten callback called when stream is closed to report total bytes written.
   * @param onInjectionTime callback called with the time in milliseconds taken to write the
   *     injection content.
   */
  public InjectingPipeOutputStream(
      final OutputStream downstream,
      final byte[] marker,
      final byte[] contentToInject,
      final Runnable onContentInjected,
      final LongConsumer onBytesWritten,
      final LongConsumer onInjectionTime) {
    this(
        downstream,
        contentToInject,
        new LiteralByteMatcher(marker),
        onContentInjected,
        onBytesWritten,
        onInjectionTime);
  }

  /**
   * Primary constructor with an explicit matcher.
   *
   * @param downstream the delegate output stream
   * @param contentToInject the content to inject once at the position chosen by the matcher.
   * @param matcher decides where to inject (e.g. {@link LiteralByteMatcher}, {@link
   *     HtmlByteMatcher}).
   * @param onContentInjected callback called when and if the content is injected.
   * @param onBytesWritten callback called when stream is closed to report total bytes written.
   * @param onInjectionTime callback called with the time in milliseconds taken to write the
   *     injection content.
   */
  public InjectingPipeOutputStream(
      final OutputStream downstream,
      final byte[] contentToInject,
      final ByteMatcher matcher,
      final Runnable onContentInjected,
      final LongConsumer onBytesWritten,
      final LongConsumer onInjectionTime) {
    this.matcher = matcher;
    this.buffer =
        new InjectingByteBuffer(
            downstream,
            contentToInject,
            matcher.lookbehindSize(),
            onContentInjected,
            onBytesWritten,
            onInjectionTime);
  }

  @Override
  public void write(int b) throws IOException {
    matcher.write(b, buffer);
  }

  @Override
  public void write(byte[] array, int off, int len) throws IOException {
    matcher.write(array, off, len, buffer);
  }

  public void commit() throws IOException {
    buffer.commit();
  }

  @Override
  public void flush() throws IOException {
    buffer.flush();
  }

  @Override
  public void close() throws IOException {
    buffer.close();
  }

  public void setFilter(boolean filter) {
    buffer.setFilter(filter);
  }
}
