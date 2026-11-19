package datadog.trace.util;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.nio.ByteBuffer;
import java.util.Base64;
import javax.annotation.Nullable;

/**
 * Basic Base64 decoding that returns {@code null} for invalid input instead of throwing.
 *
 * <p>Input whose length or padding can't be valid Base64 is rejected up front, without calling the
 * JDK decoder. Anything else is decoded with {@link Base64#getDecoder()}, and returns {@code null}
 * if the decoder rejects it.
 */
public final class Base64Decoding {
  private static final Base64.Decoder DECODER = Base64.getDecoder();

  private Base64Decoding() {}

  /** Decodes {@code src}, or returns {@code null} if it isn't valid Base64. */
  @Nullable
  public static byte[] decode(byte[] src) {
    if (!hasValidShape(src)) {
      return null;
    }
    try {
      return DECODER.decode(src);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Decodes {@code src}, or returns {@code null} if it isn't valid Base64. */
  @Nullable
  public static byte[] decode(String src) {
    return decode(src.getBytes(ISO_8859_1));
  }

  /**
   * Decodes the remaining bytes of {@code src}, or returns {@code null} if they aren't valid
   * Base64. On success the buffer is consumed, like {@link Base64.Decoder#decode(ByteBuffer)}; on
   * failure its position is unchanged.
   */
  @Nullable
  public static ByteBuffer decode(ByteBuffer src) {
    int start = src.position();
    int end = src.limit();
    if (!hasValidShape(
        end - start, at(src, start, end - 1), at(src, start, end - 2), at(src, start, end - 3))) {
      return null;
    }
    try {
      return DECODER.decode(src);
    } catch (IllegalArgumentException e) {
      // the JDK decoder restores the position before rethrowing
      return null;
    }
  }

  /** Whether the length and trailing padding of {@code src} can be valid Base64. */
  static boolean hasValidShape(byte[] src) {
    int length = src.length;
    return hasValidShape(length, at(src, length - 1), at(src, length - 2), at(src, length - 3));
  }

  /**
   * Checks the length and trailing padding: padded input must be whole 4-character groups with at
   * most two {@code '='}, and unpadded input can't end in a lone character.
   */
  private static boolean hasValidShape(int length, int last, int secondLast, int thirdLast) {
    if (last != '=') {
      return (length & 3) != 1;
    }
    return (length & 3) == 0 && !(secondLast == '=' && thirdLast == '=');
  }

  private static int at(byte[] src, int index) {
    return index >= 0 ? src[index] : -1;
  }

  private static int at(ByteBuffer src, int start, int index) {
    return index >= start ? src.get(index) : -1;
  }
}
