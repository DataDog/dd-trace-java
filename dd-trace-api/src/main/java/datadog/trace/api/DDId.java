package datadog.trace.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

/**
 * Class encapsulating the unsigned 64 bit id used for Trace and Span ids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 */
@Slf4j
public class DDId {

  public static final DDId ZERO = new DDId(0, "0");
  public static final DDId MAX = new DDId(-1, "18446744073709551615"); // All bits set

  private static final Base64Decoder BASE64 = new Base64Decoder();

  // Convenience constant used from tests
  private static final DDId ONE = DDId.from(1);

  /**
   * Generate a new unsigned 64 bit id.
   *
   * @return DDId
   */
  public static DDId generate() {
    // It is **extremely** unlikely to generate the value "0" but we still need to handle that case
    long id;
    do {
      // The ids are positive here for historical reasons, and supposed compatibility with
      // different older Datadog agent versions.
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
    try {
      return DDId.create(parseUnsignedLong(s), s);
    } catch (NumberFormatException e) {
      // we have reports of Kakfa mirror maker base64 encoding record headers
      // attempting to base64 decode the ids here rather than in the Kafka instrumentation
      // helps users understand they have a problem without making other users pay for it
      if (null != s) {
        try {
          String decoded = new String(BASE64.decode(s), StandardCharsets.ISO_8859_1);
          log.debug(
              "id {} was base 64 encoded to {}, it was decoded but this indicates there is a problem elsewhere in your system",
              decoded,
              s);
          return DDId.create(parseUnsignedLong(decoded), decoded);
        } catch (Exception ignored) {

        }
      }
      throw e;
    }
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
    try {
      long id = parseUnsignedLongHex(s);
      return DDId.create(id, null);
    } catch (NumberFormatException e) {
      // we have reports of Kakfa mirror maker base64 encoding record headers
      // attempting to base64 decode the ids here rather than in the Kafka instrumentation
      // helps users understand they have a problem without making other users pay for it
      if (null != s) {
        try {
          String decoded = new String(BASE64.decode(s), StandardCharsets.ISO_8859_1);
          log.debug(
              "id {} was base 64 encoded to {}, it was decoded but this indicates there is a problem elsewhere in your system",
              decoded,
              s);
          return DDId.create(parseUnsignedLongHex(decoded), null);
        } catch (Exception ignored) {
        }
      }
      throw e;
    }
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

    int len = s.length();
    if (len > 0) {
      if (len > 16 && (len - firstNonZeroCharacter(s)) > 16) {
        // Unsigned 64 bits max is 16 digits, so this always overflows
        throw numberFormatOutOfRange(s);
      }
      long result = 0;
      int ok = 0;
      for (int i = 0; i < len; i++) {
        char c = s.charAt(i);
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

  // TODO - can be removed when JDK7 is dropped (adapted from JDK8 source)
  private static class Base64Decoder {

    private static final int[] BASE_64 = new int[256];

    static {
      Arrays.fill(BASE_64, -1);
      int i = 0;
      for (char c = 'A'; c <= 'Z'; ++c) {
        BASE_64[c] = i++;
      }
      for (char c = 'a'; c <= 'z'; ++c) {
        BASE_64[c] = i++;
      }
      for (char c = '0'; c <= '9'; ++c) {
        BASE_64[c] = i++;
      }
      BASE_64['+'] = i++;
      BASE_64['/'] = i;
      BASE_64['='] = -2;
    }

    public byte[] decode(byte[] src) {
      byte[] dst = new byte[outLength(src, 0, src.length)];
      int ret = decode0(src, 0, src.length, dst);
      if (ret != dst.length) {
        dst = Arrays.copyOf(dst, ret);
      }
      return dst;
    }

    public byte[] decode(String src) {
      return decode(src.getBytes(StandardCharsets.ISO_8859_1));
    }

    private int outLength(byte[] src, int sp, int sl) {
      int paddings = 0;
      int len = sl - sp;
      if (len == 0) return 0;
      if (len < 2) {
        if (BASE_64[0] == -1) return 0;
        throw new IllegalArgumentException(
            "Input byte[] should at least have 2 bytes for base64 bytes");
      }
      // scan all bytes to fill out all non-alphabet. a performance
      // trade-off of pre-scan or Arrays.copyOf
      int n = 0;
      while (sp < sl) {
        int b = src[sp++] & 0xff;
        if (b == '=') {
          len -= (sl - sp + 1);
          break;
        }
        if ((b = BASE_64[b]) == -1) n++;
      }
      len -= n;
      if ((len & 0x3) != 0) paddings = 4 - (len & 0x3);
      return 3 * ((len + 3) / 4) - paddings;
    }

    private int decode0(byte[] src, int sp, int sl, byte[] dst) {
      int dp = 0;
      int bits = 0;
      int shiftto = 18; // pos of first byte of 4-byte atom

      while (sp < sl) {
        if (shiftto == 18 && sp + 4 < sl) { // fast path
          int sl0 = sp + ((sl - sp) & ~0b11);
          while (sp < sl0) {
            int b1 = BASE_64[src[sp++] & 0xff];
            int b2 = BASE_64[src[sp++] & 0xff];
            int b3 = BASE_64[src[sp++] & 0xff];
            int b4 = BASE_64[src[sp++] & 0xff];
            if ((b1 | b2 | b3 | b4) < 0) { // non base64 byte
              sp -= 4;
              break;
            }
            int bits0 = b1 << 18 | b2 << 12 | b3 << 6 | b4;
            dst[dp++] = (byte) (bits0 >> 16);
            dst[dp++] = (byte) (bits0 >> 8);
            dst[dp++] = (byte) (bits0);
          }
          if (sp >= sl) break;
        }
        int b = src[sp++] & 0xff;
        if ((b = BASE_64[b]) < 0) {
          if (b == -2) { // padding byte '='
            // =     shiftto==18 unnecessary padding
            // x=    shiftto==12 a dangling single x
            // x     to be handled together with non-padding case
            // xx=   shiftto==6&&sp==sl missing last =
            // xx=y  shiftto==6 last is not =
            if (shiftto == 6 && (sp == sl || src[sp++] != '=') || shiftto == 18) {
              throw new IllegalArgumentException("Input byte array has wrong 4-byte ending unit");
            }
            break;
          }
        }
        bits |= (b << shiftto);
        shiftto -= 6;
        if (shiftto < 0) {
          dst[dp++] = (byte) (bits >> 16);
          dst[dp++] = (byte) (bits >> 8);
          dst[dp++] = (byte) (bits);
          shiftto = 18;
          bits = 0;
        }
      }
      // reached end of byte array or hit padding '=' characters.
      if (shiftto == 6) {
        dst[dp++] = (byte) (bits >> 16);
      } else if (shiftto == 0) {
        dst[dp++] = (byte) (bits >> 16);
        dst[dp++] = (byte) (bits >> 8);
      } else if (shiftto == 12) {
        // dangling single "x", incorrectly encoded.
        throw new IllegalArgumentException("Last unit does not have enough valid bits");
      }
      // ignore all non-base64 character
      while (sp < sl) {
        if (BASE_64[src[sp++] & 0xff] < 0) continue;
        throw new IllegalArgumentException("Input byte array has incorrect ending byte at " + sp);
      }
      return dp;
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
    if (o == null || getClass() != o.getClass()) return false;
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
