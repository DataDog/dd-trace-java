package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;
import java.io.Writer;
import java.util.function.LongConsumer;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A Writer that injects content once, at a position chosen by a {@link CharMatcher}. It is a thin
 * composition of the buffering ({@link InjectingCharBuffer}) and the matching (the {@link
 * CharMatcher}); all the work lives in those two collaborators.
 */
@NotThreadSafe
public class InjectingPipeWriter extends Writer {
  private final InjectingCharBuffer buffer;
  private final CharMatcher matcher;

  /**
   * Convenience constructor using literal marker matching (typically used for testing).
   *
   * @param downstream the delegate writer
   * @param marker the marker to find in the stream. Must at least be one char.
   * @param contentToInject the content to inject once before the marker if found.
   */
  public InjectingPipeWriter(
      final Writer downstream, final char[] marker, final char[] contentToInject) {
    this(downstream, contentToInject, new LiteralCharMatcher(marker), null, null, null);
  }

  /**
   * Convenience constructor using literal marker matching with the full set of callbacks.
   *
   * @param downstream the delegate writer
   * @param marker the marker to find in the stream. Must at least be one char.
   * @param contentToInject the content to inject once before the marker if found.
   * @param onContentInjected callback called when and if the content is injected.
   * @param onBytesWritten callback called when writer is closed to report total bytes written.
   * @param onInjectionTime callback called with the time in milliseconds taken to write the
   *     injection content.
   */
  public InjectingPipeWriter(
      final Writer downstream,
      final char[] marker,
      final char[] contentToInject,
      final Runnable onContentInjected,
      final LongConsumer onBytesWritten,
      final LongConsumer onInjectionTime) {
    this(
        downstream,
        contentToInject,
        new LiteralCharMatcher(marker),
        onContentInjected,
        onBytesWritten,
        onInjectionTime);
  }

  /**
   * Primary constructor with an explicit matcher.
   *
   * @param downstream the delegate writer
   * @param contentToInject the content to inject once at the position chosen by the matcher.
   * @param matcher decides where to inject (e.g. {@link LiteralCharMatcher}, {@link
   *     HtmlCharMatcher}).
   * @param onContentInjected callback called when and if the content is injected.
   * @param onBytesWritten callback called when writer is closed to report total bytes written.
   * @param onInjectionTime callback called with the time in milliseconds taken to write the
   *     injection content.
   */
  public InjectingPipeWriter(
      final Writer downstream,
      final char[] contentToInject,
      final CharMatcher matcher,
      final Runnable onContentInjected,
      final LongConsumer onBytesWritten,
      final LongConsumer onInjectionTime) {
    this.matcher = matcher;
    this.buffer =
        new InjectingCharBuffer(
            downstream,
            contentToInject,
            matcher.lookbehindSize(),
            onContentInjected,
            onBytesWritten,
            onInjectionTime);
  }

  @Override
  public void write(int c) throws IOException {
    matcher.write(c, buffer);
  }

  @Override
  public void write(char[] array, int off, int len) throws IOException {
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
