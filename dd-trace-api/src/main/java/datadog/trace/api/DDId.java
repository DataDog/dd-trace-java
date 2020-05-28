package datadog.trace.api;

import java.math.BigInteger;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Class encapsulating the unsigned 64 bit id used for Trace and Span ids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The strings are either kept from parsing, or generated on demand and cached.
 */
public class DDId {

  public static final DDId ZERO = new DDId(0, "0", "0");
  public static final DDId MAX =
      new DDId(-1, "18446744073709551615", "ffffffffffffffff"); // All bits set

  // Convenience constant used from tests
  public static final DDId ONE = DDId.from(1);

  /**
   * Generate a new unsigned 64 bit id.
   *
   * @return DDId
   */
  public static DDId generate() {
    // TODO should we use the top bit as well here?
    // It is **extremely** unlikely to generate the value "0" but we still need to handle that
    // case
    long id;
    do {
      id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    } while (id == 0);
    return DDId.from(id);
  }

  /**
   * Create a new {@code DDId} from the given {@code long} interpreted as the bits of the unsigned
   * 64 bit id. This means that values larger than Long.MAX_VALUE will be represented as negative
   * numbers.
   *
   * @param id long representing the bits of the unsigned 64 bit id
   * @return DDId
   */
  public static DDId from(long id) {
    return DDId.create(id, null, null);
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
    return DDId.create(parseUnsignedLong(s), s, null);
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
    long id = parseUnsignedLongHex(s);
    // The hex string should be lower case and non zero padded
    String hex = s.toLowerCase();
    int firstNonZero = firstNonZeroCharacter(s);
    if (firstNonZero > 0) {
      hex = hex.substring(firstNonZero);
    }
    return DDId.create(id, null, hex);
  }

  private final long id;
  private String str; // cache for string representation
  private String hex; // cache for hex string representation
  // TODO This is only here since msgpack doesn't support packUnsignedLong
  //      so we need to write out "negative" longs as a BigInteger
  private BigInteger big;

  private DDId(long id, String str, String hex) {
    this.id = id;
    this.str = str;
    this.hex = hex;
  }

  private static DDId create(long id, String str, String hex) {
    if (id == 0) return ZERO;
    if (id == -1) return MAX;
    return new DDId(id, str, hex);
  }

  private static int firstNonZeroCharacter(String s) {
    int firstNonZero = 0;
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
        for (int i = 0; i < len - 1; i++) {
          char c = s.charAt(i);
          int d = Character.digit(c, 10);
          if (d < 0) {
            throw new NumberFormatException("Illegal character '" + c + "' in " + s);
          }
          first = first * 10 + d;
        }
        int last = Character.digit(s.charAt(len - 1), 10);
        if (last < 0) {
          throw new NumberFormatException("Bad digit at end of " + s);
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

    int len = s.length();
    if (len > 0) {
      if (len > 16 && (len - firstNonZeroCharacter(s)) > 16) {
        // Unsigned 64 bits max is 16 digits, so this always overflows
        throw numberFormatOutOfRange(s);
      }
      long result = 0;
      for (int i = 0; i < len; i++) {
        char c = s.charAt(i);
        int d = Character.digit(c, 16);
        if (d < 0) {
          throw new NumberFormatException("Illegal character '" + c + "' in " + s);
        }
        result = result << 4 | d;
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

  // TODO Can be removed when msgpack support packUnsignedLong
  private static BigInteger toUnsignedBigInteger(long l) {
    if (l >= 0L) return BigInteger.valueOf(l);

    int high = (int) (l >>> 32);
    int low = (int) l;

    return (BigInteger.valueOf(((long) high) & 0xffffffffL))
        .shiftLeft(32)
        .add(BigInteger.valueOf(((long) low) & 0xffffffffL));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DDId ddId = (DDId) o;
    return this.id == ddId.id;
  }

  @Override
  public int hashCode() {
    return (int) this.id;
  }

  @Override
  public String toString() {
    String s = this.str;
    if (s == null) {
      this.str = s = toUnsignedString(this.id);
    }
    return s;
  }

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id.
   *
   * @return non zero padded hex String
   */
  public String toHexString() {
    String h = this.hex;
    if (hex == null) {
      this.hex = h = Long.toHexString(this.id);
    }
    return h;
  }

  /**
   * Returns a {@code BigInteger} representation of the 64 bit id. TODO Can be removed if msgpack
   * supports packUnsignedLong
   *
   * @return BigInteger representation of the 64 it id.
   */
  public BigInteger toBigInteger() {
    BigInteger b = this.big;
    if (b == null) {
      this.big = b = toUnsignedBigInteger(this.id);
    }
    return b;
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
}
