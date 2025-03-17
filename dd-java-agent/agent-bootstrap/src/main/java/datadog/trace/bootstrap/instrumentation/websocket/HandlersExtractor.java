package datadog.trace.bootstrap.instrumentation.websocket;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.function.ToIntFunction;
import javax.annotation.Nonnull;

public class HandlersExtractor {
  public static final CharSequence MESSAGE_TYPE_TEXT = UTF8BytesString.create("text");
  public static final CharSequence MESSAGE_TYPE_BINARY = UTF8BytesString.create("binary");

  static class CharSequenceLenFunction implements ToIntFunction<CharSequence> {
    @Override
    public int applyAsInt(CharSequence value) {
      return value != null ? value.length() : 0;
    }
  }

  static class ByteArrayLenFunction implements ToIntFunction<byte[]> {
    @Override
    public int applyAsInt(byte[] value) {
      return value != null ? value.length : 0;
    }
  }

  static class ByteBufferLenFunction implements ToIntFunction<ByteBuffer> {
    @Override
    public int applyAsInt(ByteBuffer value) {
      return value != null ? value.remaining() : 0;
    }
  }

  static class NoopLenFunction implements ToIntFunction<Object> {
    @Override
    public int applyAsInt(Object ignored) {
      return 0;
    }
  }

  public static class SizeCalculator<T> {
    @Nonnull private final ToIntFunction<T> lengthFunction;
    @Nonnull private final CharSequence format;

    SizeCalculator(@Nonnull ToIntFunction<T> lengthFunction, @Nonnull CharSequence format) {
      this.lengthFunction = lengthFunction;
      this.format = format;
    }

    @Nonnull
    public ToIntFunction<T> getLengthFunction() {
      return lengthFunction;
    }

    @Nonnull
    public CharSequence getFormat() {
      return format;
    }
  }

  public static final SizeCalculator<CharSequence> CHAR_SEQUENCE_SIZE_CALCULATOR =
      new SizeCalculator<>(new CharSequenceLenFunction(), MESSAGE_TYPE_TEXT);
  public static final SizeCalculator<byte[]> BYTES_SIZE_CALCULATOR =
      new SizeCalculator<>(new ByteArrayLenFunction(), MESSAGE_TYPE_BINARY);
  public static final SizeCalculator<ByteBuffer> BYTE_BUFFER_SIZE_CALCULATOR =
      new SizeCalculator<>(new ByteBufferLenFunction(), MESSAGE_TYPE_BINARY);
  public static final SizeCalculator<Object> TEXT_STREAM_SIZE_CALCULATOR =
      new SizeCalculator<>(new NoopLenFunction(), MESSAGE_TYPE_TEXT);
  public static final SizeCalculator<Object> BYTE_STREAM_SIZE_CALCULATOR =
      new SizeCalculator<>(new NoopLenFunction(), MESSAGE_TYPE_BINARY);

  public static SizeCalculator getSizeCalculator(Object data) {
    // we only extract "safely" the message size from byte[], ByteBuffer and String.
    // Other types will contain streaming data (i.e. InputStream, Reader)
    if (data instanceof CharSequence) {
      return CHAR_SEQUENCE_SIZE_CALCULATOR;
    } else if (data instanceof byte[]) {
      return BYTES_SIZE_CALCULATOR;
    } else if (data instanceof ByteBuffer) {
      return BYTE_BUFFER_SIZE_CALCULATOR;
    } else if (data instanceof Reader) {
      return TEXT_STREAM_SIZE_CALCULATOR;
    } else if (data instanceof InputStream) {
      return BYTE_STREAM_SIZE_CALCULATOR;
    }
    return null;
  }

  private HandlersExtractor() {}
}
