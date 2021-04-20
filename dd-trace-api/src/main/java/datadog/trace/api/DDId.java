package datadog.trace.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Class encapsulating the unsigned 64 bit id used for Trace and Span ids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 */
public class DDId {

  public static final DDId ZERO = new DDId(0, "0");
  public static final DDId MAX = new DDId(-1, "18446744073709551615"); // All bits set

  // Convenience constant used from tests
  private static final DDId ONE = DDId.from(1);

  /**
   * Create a new {@code DDId} from the given {@code long} interpreted as the bits of the unsigned
   * 64 bit id. This means that values larger than Long.MAX_VALUE will be represented as negative
   * numbers.
   *
   * @param id long representing the bits of the unsigned 64 bit id
   * @return DDId
   */
  public static DDId from(long id) {
    return DDId.create(id, null);
  }

  /**
   * Create a new {@code DDId} from the given {@code String} representation of the unsigned 64 bit
   * id.
   *
   * @param s String of unsigned 64 bit id
   * @return DDId
   * @throws NumberFormatException
   */
  public static DDId from(String s) throws NumberFormatException {
    return DDId.create(parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@code DDId} from the given {@code String} hex representation of the unsigned 64
   * bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDId
   * @throws NumberFormatException
   */
  public static DDId fromHex(String s) throws NumberFormatException {
    return DDId.create(parseUnsignedLongHex(s), null);
  }

  /**
   * Create a new {@code DDId} from the given {@code String} hex representation of the unsigned 64
   * bit id, while retalining the original {@code String} representation for use in headers.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDId
   * @throws NumberFormatException
   */
  public static DDId fromHexWithOriginal(String s) throws NumberFormatException {
    return new DDIdOriginal(parseUnsignedLongHex(s), s);
  }

  /**
   * Create a new {@code DDId} from the given {@code String} hex representation of the unsigned 64
   * bit (or more) id truncated to 64 bits, while retaining the original {@code String}
   * representation for use in headers.
   *
   * @param s String in hex of unsigned 64 bit (or more) id
   * @return DDId
   * @throws NumberFormatException
   */
  public static DDId fromHexTruncatedWithOriginal(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    int len = s.length();
    int trimmed = Math.min(s.length(), 16);
    return new DDIdOriginal(parseUnsignedLongHex(s, len - trimmed, trimmed), s);
  }

  private final long id;
  private String str; // cache for string representation

  private DDId(long id, String str) {
    this.id = id;
    this.str = str;
  }

  private static DDId create(long id, String str) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DDId(id, str);
  }

  private static int firstNonZeroCharacter(String s, int start) {
    int firstNonZero = start;
    for (; firstNonZero < s.length(); firstNonZero++) {
      if (s.charAt(firstNonZero) != '0') break;
    }
    return firstNonZero;
  }

  private static NumberFormatException numberFormatOutOfRange(String s) {
    return new NumberFormatException(
        String.format("String value %s exceeds range of unsigned long.", s));
  }

  private static long MAX_FIRST_PART = 0x1999999999999999L; // Max unsigned 64 bits / 10

  private static long parseUnsignedLong(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    int len = s.length();
    if (len > 0) {
      char firstChar = s.charAt(0);
      if (firstChar == '-') {
        throw new NumberFormatException(
            String.format("Illegal leading minus sign on unsigned string %s.", s));
      } else {
        if (len <= 18) { // Signed 64 bits max is 19 digits, so this always fits
          return Long.parseLong(s);
        } else if (len > 20) { // Unsigned 64 bits max is 20 digits, so this always overflows
          throw numberFormatOutOfRange(s);
        }
        // Now do the first part and the last character
        long first = 0;
        int ok = 0;
        for (int i = 0; i < len - 1; i++) {
          char c = s.charAt(i);
          int d = Character.digit(c, 10);
          ok |= d;
          first = first * 10 + d;
        }
        int last = Character.digit(s.charAt(len - 1), 10);
        ok |= last;
        if (ok < 0) {
          throw new NumberFormatException("Illegal character in " + s);
        }
        if (first > MAX_FIRST_PART) {
          throw numberFormatOutOfRange(s);
        }
        long guard = first * 10;
        long result = guard + last;
        if (guard < 0 && result >= 0) {
          throw numberFormatOutOfRange(s);
        }
        return result;
      }
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  private static long parseUnsignedLongHex(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    return parseUnsignedLongHex(s, 0, s.length());
  }

  private static long parseUnsignedLongHex(String s, int start, int len)
      throws NumberFormatException {
    if (len > 0) {
      if (len > 16 && (len - firstNonZeroCharacter(s, start)) > 16) {
        // Unsigned 64 bits max is 16 digits, so this always overflows
        throw numberFormatOutOfRange(s);
      }
      long result = 0;
      int ok = 0;
      for (int i = 0; i < len; i++, start++) {
        char c = s.charAt(start);
        int d = Character.digit(c, 16);
        ok |= d;
        result = result << 4 | d;
      }
      if (ok < 0) {
        throw new NumberFormatException("Illegal character in " + s);
      }
      return result;
    } else {
      throw new NumberFormatException("Empty input string");
    }
  }

  // TODO Can be removed when Java7 support is removed
  private static String toUnsignedString(long l) {
    if (l >= 0) return Long.toString(l);

    // shift left once and divide by 5 results in an unsigned divide by 10
    long quot = (l >>> 1) / 5;
    long rem = l - quot * 10;
    return Long.toString(quot) + rem;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DDId)) return false;
    DDId ddId = (DDId) o;
    return this.id == ddId.id;
  }

  @Override
  public int hashCode() {
    long id = this.id;
    return (int) (id ^ (id >>> 32));
  }

  /**
   * Returns the decimal string representation of the unsigned 64 bit id. The {@code String} will be
   * cached.
   *
   * @return decimal string
   */
  @Override
  public String toString() {
    String s = this.str;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (s == null) {
      this.str = s = toUnsignedString(this.id);
    }
    return s;
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id. The
   * hex {@code String} will NOT be cached.
   *
   * @return non zero padded hex String
   */
  public String toHexString() {
    return Long.toHexString(this.id);
  }

  private static final byte[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  /**
   * Returns the zero padded hex representation, in lower case, of the unsigned 64 bit id. The size
   * will be rounded up to 16 or 32 characters. The hex {@code String} will NOT be cached.
   *
   * @param size the size in characters of the 0 padded String (rounded up to 16 or 32)
   * @return zero padded hex String
   */
  public String toHexStringPadded(int size) {
    if (size > 16) {
      size = 32;
    } else if (size < 16) {
      size = 16;
    }
    byte[] bytes = new byte[size];
    long remaining = this.id;
    int nibbleCount = Long.numberOfLeadingZeros(remaining) >>> 2;
    Arrays.fill(bytes, 0, (size - 16) + nibbleCount, (byte) '0');
    for (int i = 0; i < 16 - nibbleCount; i++) {
      int b = (int) (remaining & 0xF);
      bytes[size - 1 - i] = HEX_DIGITS[b];
      remaining >>>= 4;
    }
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id, or the
   * original {@code String} used to create this {@code DDId}. The hex {@code String} will NOT be
   * cached.
   *
   * @return non zero padded hex String
   */
  public String toHexStringOrOriginal() {
    return this.toHexString();
  }

  /**
   * Returns the id as a long representing the bits of the unsigned 64 bit id. This means that
   * values larger than Long.MAX_VALUE will be represented as negative numbers.
   *
   * @return long value representing the bits of the unsigned 64 bit id.
   */
  public long toLong() {
    return this.id;
  }

  /**
   * Class representing a {@code DDId} that maintains the original {@code String} representation to
   * be used when propagating it in headers.
   */
  private static final class DDIdOriginal extends DDId {
    private final String original;

    private DDIdOriginal(long id, String original) {
      super(id, null);
      this.original = original;
    }

    @Override
    public String toHexStringOrOriginal() {
      return this.original;
    }
  }
}
