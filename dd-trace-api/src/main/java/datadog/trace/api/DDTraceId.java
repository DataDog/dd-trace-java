package datadog.trace.api;

import datadog.trace.api.internal.util.HexStringUtils;

/**
 * Class encapsulating the id used for TraceIds.
 *
 * <p>It contains parsing and formatting to string for both decimal and hexadecimal representations.
 * The string representations are either kept from parsing, or generated on demand and cached.
 */
public interface DDTraceId {
  DDTraceId ZERO = DD64bTraceId.ZERO;

  /**
   * Create a new {@code DDTrace64Id} from the given {@code long} interpreted as the bits of the
   * unsigned 64-bit id. This means that values larger than {@link Long#MAX_VALUE} will be
   * represented as negative numbers. Use {@link DD64bTraceId#from(long)} instead.
   *
   * @param id The {@code long} representing the bits of the unsigned 64-bit id.
   * @return A new {@link DDTraceId} instance.
   */
  @Deprecated
  static DDTraceId from(long id) {
    return DD64bTraceId.from(id);
  }

  /**
   * Create a new {@link DDTraceId} from the given {@link String} representation.
   *
   * @param s The {@link String} representation of a {@link DDTraceId}.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given {@link String} is not a valid number.
   */
  static DDTraceId from(String s) throws NumberFormatException {
    return DD64bTraceId.create(DDId.parseUnsignedLong(s), s); // TODO Support 128b
  }

  /**
   * Create a new {@link DDTraceId} from the given {@link String} hexadecimal representation.
   *
   * @param s The hexadecimal {@link String} representation of a {@link DD128bTraceId} to parse.
   * @return A new {@link DDTraceId} instance.
   * @throws NumberFormatException If the given hexadecimal {@link String} does not represent a
   *     valid number.
   */
  static DDTraceId fromHex(String s) throws NumberFormatException {
    return DD64bTraceId.create(DDId.parseUnsignedLongHex(s), null); // TODO Support 128b
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit (or more) id truncated to 64 bits, while retaining the original {@code String}
   * representation for use in headers.
   *
   * @param s String in hex of unsigned 64 bit (or more) id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  static DDTraceId fromHexTruncatedWithOriginal(String s) throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }

    return fromHexTruncatedWithOriginal(s, 0, s.length(), false);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit (or more) id truncated to 64 bits, while retaining the original {@code String}
   * representation for use in headers.
   *
   * @param s String in hex of unsigned 64 bit (or more) id
   * @param start the start index of the hex value
   * @param len the len of the hex value
   * @param lowerCaseOnly if the allowed hex characters are lower case only
   * @return DDTraceId
   * @throws NumberFormatException
   */
  static DDTraceId fromHexTruncatedWithOriginal(String s, int start, int len, boolean lowerCaseOnly)
      throws NumberFormatException {
    if (s == null) {
      throw new NumberFormatException("null");
    }
    int size = s.length();
    if (start < 0 || len <= 0 || len > 32 || start + len > size) {
      throw new NumberFormatException("Illegal start or length");
    }
    int trimmed = Math.min(len, 16);
    int end = start + len;
    int trimmedStart = end - trimmed;
    if (trimmedStart > 0) {
      // we need to check that the characters we don't parse are valid
      HexStringUtils.parseUnsignedLongHex(s, start, trimmedStart, lowerCaseOnly);
    }
    String original;
    if (start == 0 && end == len) {
      original = s;
    } else {
      original = s.substring(start, end);
    }
    return new DD64bTraceId(
        HexStringUtils.parseUnsignedLongHex(s, trimmedStart, trimmed, lowerCaseOnly),
        null,
        original);
  }

  /**
   * Returns the decimal {@link String} representation of the {@link DDTraceId}. The {@link String}
   * will be cached.
   *
   * @return A cached {@link String} representation of the {@link DDTraceId} instance.
   */
  @Override
  String toString();

  /**
   * Returns the lower-case non zero-padded hexadecimal {@link String} representation of the {@link
   * DDTraceId}. This hexadecimal {@code String} <strong>will not be cached</strong>.
   *
   * @return A lower-case non zero-padded hexadecimal {@link String} representation of the {@link
   *     DDTraceId} instance.
   */
  String toHexString();

  /**
   * Returns the lower-case zero-padded hexadecimal {@link String} representation of the {@link
   * DDTraceId}. The size will be rounded up to <code>16</code> or <code>32</code> characters. This
   * hexadecimal {@code String} <strong>will not be cached</strong>.
   *
   * @param size The size in characters of the zero-padded {@link String} (rounded up to <code>16
   *     </code> or <code>32</code>).
   * @return A lower-case zero-padded hexadecimal {@link String} representation of the {@link
   *     DDTraceId} instance.
   */
  String toHexStringPadded(int size);

  /**
   * Returns the no zero padded hex representation, in lower case, of the unsigned 64 bit id, or the
   * original {@code String} used to create this {@code DDId}. The hex {@code String} will NOT be
   * cached.
   *
   * @return non zero padded hex String
   */
  String toHexStringOrOriginal(); // TODO Cleanup?

  /**
   * Returns the zero padded hex representation, in lower case, of the unsigned 64 bit id or the
   * original. The size will be rounded up to 16 or 32 characters. The hex {@code String} will NOT
   * be cached.
   *
   * @param size the size in characters of the 0 padded String (rounded up to 16 or 32)
   * @return zero padded hex String
   */
  String toHexStringPaddedOrOriginal(int size); // TODO Cleanup?

  /**
   * Returns the id as a long representing the bits of the unsigned 64 bit id. This means that
   * values larger than Long.MAX_VALUE will be represented as negative numbers. Use {@link
   * DD64bTraceId#toLong()} instead.
   *
   * @return long value representing the bits of the unsigned 64 bit id.
   */
  @Deprecated
  long toLong(); // TODO Cleanup?
}
