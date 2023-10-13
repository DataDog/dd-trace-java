package datadog.trace.api.git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * Contains utility methods to be used in the byte[] content of a certain Git object. This class is
 * based on the RawParseUtils class which is kept in the JGit library.
 * https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/util/RawParseUtils.java
 */
public final class RawParseUtils {

  private RawParseUtils() {}

  public static final byte[] COMMITTER = "committer ".getBytes();
  public static final byte[] AUTHOR = "author ".getBytes();

  private static final byte[] digits10;

  static {
    digits10 = new byte['9' + 1];
    Arrays.fill(digits10, (byte) -1);
    for (char i = '0'; i <= '9'; i++) {
      digits10[i] = (byte) (i - '0');
    }
  }

  /**
   * Locate the position of the commit message body.
   *
   * @param b buffer to scan.
   * @param ptr position in buffer to start the scan at. Most callers should pass 0 to ensure the
   *     scan starts from the beginning of the commit buffer.
   * @return position of the user's message buffer.
   */
  public static int commitMessage(final byte[] b, int ptr) {
    final int sz = b.length;
    if (ptr == 0) {
      ptr += 46; // skip the "tree ..." line.
    }
    while (ptr < sz && b[ptr] == 'p') {
      ptr += 48; // skip this parent.
    }

    // Skip any remaining header lines, ignoring what their actual
    // header line type is. This is identical to the logic for a tag.
    //
    return tagMessage(b, ptr);
  }

  /**
   * Locate the position of the tag message body.
   *
   * @param b buffer to scan.
   * @param ptr position in buffer to start the scan at. Most callers should pass 0 to ensure the
   *     scan starts from the beginning of the tag buffer.
   * @return position of the user's message buffer.
   */
  public static int tagMessage(final byte[] b, int ptr) {
    final int sz = b.length;
    if (ptr == 0) {
      ptr += 48; // skip the "object ..." line.
    }
    while (ptr < sz && b[ptr] != '\n') {
      ptr = nextLF(b, ptr);
    }
    if (ptr < sz && b[ptr] == '\n') {
      return ptr + 1;
    }
    return -1;
  }

  /**
   * Locate the "committer " header line data.
   *
   * @param b buffer to scan.
   * @param ptr position in buffer to start the scan at. Most callers should pass 0 to ensure the
   *     scan starts from the beginning of the commit buffer and does not accidentally look at
   *     message body.
   * @return position just after the space in "committer ", so the first character of the
   *     committer's name. If no committer header can be located -1 is returned.
   */
  public static int committer(final byte[] b, int ptr) {
    final int sz = b.length;
    if (ptr == 0) {
      ptr += 46; // skip the "tree ..." line.
    }
    while (ptr < sz && b[ptr] == 'p') {
      ptr += 48; // skip this parent.
    }
    if (ptr < sz && b[ptr] == 'a') {
      ptr = nextLF(b, ptr);
    }
    return match(b, ptr, COMMITTER);
  }

  /**
   * Locate the "author " header line data.
   *
   * @param b buffer to scan.
   * @param ptr position in buffer to start the scan at. Most callers should pass 0 to ensure the
   *     scan starts from the beginning of the commit buffer and does not accidentally look at
   *     message body.
   * @return position just after the space in "author ", so the first character of the author's
   *     name. If no author header can be located -1 is returned.
   */
  public static final int author(final byte[] b, int ptr) {
    final int sz = b.length;
    if (ptr == 0) {
      ptr += 46; // skip the "tree ..." line.
    }
    while (ptr < sz && b[ptr] == 'p') {
      ptr += 48; // skip this parent.
    }
    return match(b, ptr, AUTHOR);
  }

  /**
   * Locate the first position after the next LF.
   *
   * <p>This method stops on the first '\n' it finds.
   *
   * @param b buffer to scan.
   * @param ptr position within buffer to start looking for LF at.
   * @return new position just after the first LF found.
   */
  public static int nextLF(final byte[] b, final int ptr) {
    return next(b, ptr, '\n');
  }

  /**
   * Locate the first position after either the given character or LF.
   *
   * <p>This method stops on the first match it finds from either chrA or '\n'.
   *
   * @param b buffer to scan.
   * @param ptr position within buffer to start looking for chrA or LF at.
   * @param chrA character to find.
   * @return new position just after the first chrA or LF to be found.
   */
  public static int nextLF(final byte[] b, int ptr, final char chrA) {
    final int sz = b.length;
    while (ptr < sz) {
      final byte c = b[ptr++];
      if (c == chrA || c == '\n') {
        return ptr;
      }
    }
    return ptr;
  }

  /**
   * Locate the first position after a given character.
   *
   * @param b buffer to scan.
   * @param ptr position within buffer to start looking for chrA at.
   * @param chrA character to find.
   * @return new position just after chrA.
   */
  public static int next(final byte[] b, int ptr, final char chrA) {
    final int sz = b.length;
    while (ptr < sz) {
      if (b[ptr++] == chrA) {
        return ptr;
      }
    }
    return ptr;
  }

  /**
   * Determine if b[ptr] matches src.
   *
   * @param b the buffer to scan.
   * @param ptr first position within b, this should match src[0].
   * @param src the buffer to test for equality with b.
   * @return ptr + src.length if b[ptr..src.length] == src; else -1.
   */
  public static int match(final byte[] b, int ptr, final byte[] src) {
    if (ptr + src.length > b.length) {
      return -1;
    }
    for (int i = 0; i < src.length; i++, ptr++) {
      if (b[ptr] != src[i]) {
        return -1;
      }
    }
    return ptr;
  }

  /**
   * Decode a region of the buffer under the specified character set if possible.
   *
   * <p>If the byte stream cannot be decoded that way, the platform default is tried and if that too
   * fails, the fail-safe ISO-8859-1 encoding is tried.
   *
   * @param buffer buffer to pull raw bytes from.
   * @param start first position within the buffer to take data from.
   * @param end one position past the last location within the buffer to take data from.
   * @return a string representation of the range <code>[start,end)</code>, after decoding the
   *     region through the specified character set.
   */
  public static String decode(final byte[] buffer, final int start, final int end) {
    final ByteBuffer b = ByteBuffer.wrap(buffer, start, end - start);
    b.mark();

    try {
      final CharsetDecoder d = UTF_8.newDecoder();
      d.onMalformedInput(CodingErrorAction.REPORT);
      d.onUnmappableCharacter(CodingErrorAction.REPORT);
      return d.decode(b).toString();
    } catch (final CharacterCodingException e) {
      b.reset();

      // Fall back to an ISO-8859-1 style encoding. At least all of
      // the bytes will be present in the output.
      //
      return extractBinaryString(buffer, start, end);
    }
  }

  /**
   * Decode a region of the buffer under the ISO-8859-1 encoding.
   *
   * <p>Each byte is treated as a single character in the 8859-1 character encoding, performing a
   * raw binary-&gt;char conversion.
   *
   * @param buffer buffer to pull raw bytes from.
   * @param start first position within the buffer to take data from.
   * @param end one position past the last location within the buffer to take data from.
   * @return a string representation of the range <code>[start,end)</code>.
   */
  public static String extractBinaryString(final byte[] buffer, final int start, final int end) {
    final StringBuilder r = new StringBuilder(end - start);
    for (int i = start; i < end; i++) {
      r.append((char) (buffer[i] & 0xff));
    }
    return r.toString();
  }

  /**
   * Get last index of {@code ch} in raw, trimming spaces.
   *
   * @param raw buffer to scan.
   * @param ch character to find.
   * @param pos starting position.
   * @return last index of {@code ch} in raw, trimming spaces.
   * @since 4.1
   */
  public static int lastIndexOfTrim(final byte[] raw, final char ch, int pos) {
    while (pos >= 0 && raw[pos] == ' ') {
      pos--;
    }

    while (pos >= 0 && raw[pos] != ch) {
      pos--;
    }

    return pos;
  }

  /**
   * Parse a base 10 numeric from a sequence of ASCII digits into a long.
   *
   * <p>Digit sequences can begin with an optional run of spaces before the sequence, and may start
   * with a '+' or a '-' to indicate sign position. Any other characters will cause the method to
   * stop and return the current result to the caller.
   *
   * @param b buffer to scan.
   * @param ptr position within buffer to start parsing digits at.
   * @return the value at this location; 0 if the location is not a valid numeric.
   */
  public static long parseLongBase10(final byte[] b, int ptr) {
    long r = 0;
    int sign = 0;
    try {
      final int sz = b.length;
      while (ptr < sz && b[ptr] == ' ') {
        ptr++;
      }
      if (ptr >= sz) {
        return 0;
      }

      switch (b[ptr]) {
        case '-':
          sign = -1;
          ptr++;
          break;
        case '+':
          ptr++;
          break;
        default:
          break;
      }

      while (ptr < sz) {
        final byte v = digits10[b[ptr]];
        if (v < 0) {
          break;
        }
        r = (r * 10) + v;
        ptr++;
      }
    } catch (final ArrayIndexOutOfBoundsException e) {
      // Not a valid digit.
    }
    return sign < 0 ? -r : r;
  }

  /**
   * Parse a base 10 numeric from a sequence of ASCII digits into an int.
   *
   * <p>Digit sequences can begin with an optional run of spaces before the sequence, and may start
   * with a '+' or a '-' to indicate sign position. Any other characters will cause the method to
   * stop and return the current result to the caller.
   *
   * @param b buffer to scan.
   * @param ptr position within buffer to start parsing digits at.
   * @return the value at this location; 0 if the location is not a valid numeric.
   */
  public static int parseBase10(final byte[] b, int ptr) {
    int r = 0;
    int sign = 0;
    try {
      final int sz = b.length;
      while (ptr < sz && b[ptr] == ' ') {
        ptr++;
      }
      if (ptr >= sz) {
        return 0;
      }

      switch (b[ptr]) {
        case '-':
          sign = -1;
          ptr++;
          break;
        case '+':
          ptr++;
          break;
        default:
          break;
      }

      while (ptr < sz) {
        final byte v = digits10[b[ptr]];
        if (v < 0) {
          break;
        }
        r = (r * 10) + v;
        ptr++;
      }
    } catch (final ArrayIndexOutOfBoundsException e) {
      // Not a valid digit.
    }
    return sign < 0 ? -r : r;
  }

  /**
   * Parse a Git style timezone string.
   *
   * <p>The sequence "-0315" will be parsed as the numeric value -195, as the lower two positions
   * count minutes, not 100ths of an hour.
   *
   * @param b buffer to scan.
   * @param ptr position within buffer to start parsing digits at.
   * @return the timezone at this location, expressed in minutes.
   * @since 4.1
   */
  public static int parseTimeZoneOffset(final byte[] b, final int ptr) {
    final int v = parseBase10(b, ptr);
    final int tzMins = v % 100;
    final int tzHours = v / 100;
    return tzHours * 60 + tzMins;
  }

  public static int findByte(final byte[] bytes, final byte b) {
    int i = 0;
    while (i < bytes.length) {
      if (bytes[i] == b) {
        return i;
      }
      i += 1;
    }
    return -1;
  }
}
