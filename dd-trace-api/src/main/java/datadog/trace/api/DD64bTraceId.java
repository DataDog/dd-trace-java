package datadog.trace.api;

import datadog.trace.api.internal.util.LongStringUtils;

/**
 * Class encapsulating the unsigned 64 bit id used for Traceids.
 *
 * <p>It contains generation of new ids, parsing, and to string for both decimal and hex
 * representations. The decimal string representation is either kept from parsing, or generated on
 * demand and cached.
 */
public class DD64bTraceId extends DDTraceId {
  public static final DD64bTraceId MAX =
      new DD64bTraceId(-1, "18446744073709551615"); // All bits set

  private final long id;
  private String str; // cache for string representation
  private String hexStr; // cache for hex string representation

  DD64bTraceId(long id, String str) {
    this.id = id;
    this.str = str;
  }

  /**
   * Create a new {@link DD64bTraceId} from the given {@code long} interpreted as the bits of the
   * unsigned 64-bit id. This means that values larger than Long.MAX_VALUE will be represented as
   * negative numbers.
   *
   * @param id The {@code long} representing the bits of the unsigned 64-bit id.
   * @return A new {@link DD64bTraceId} instance.
   */
  public static DD64bTraceId from(long id) {
    return DD64bTraceId.create(id, null);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} representation of the unsigned 64
   * bit id.
   *
   * @param s String of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DD64bTraceId from(String s) throws NumberFormatException {
    return DD64bTraceId.create(LongStringUtils.parseUnsignedLong(s), s);
  }

  /**
   * Create a new {@code DDTraceId} from the given {@code String} hex representation of the unsigned
   * 64 bit id.
   *
   * @param s String in hex of unsigned 64 bit id
   * @return DDTraceId
   * @throws NumberFormatException
   */
  public static DD64bTraceId fromHex(String s) throws NumberFormatException {
    return DD64bTraceId.create(LongStringUtils.parseUnsignedLongHex(s), null);
  }

  static DD64bTraceId create(long id, String str) {
    // ZERO constant is created and stored by the parent class as part of its API contract
    // But initialized by this 64-bit child class. Ensures uniqueness of ZERO once created.
    if (id == 0 && ZERO != null) {
      return (DD64bTraceId) ZERO;
    } else if (id == -1) {
      return MAX;
    } else {
      return new DD64bTraceId(id, str);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DD64bTraceId)) return false;
    DD64bTraceId ddId = (DD64bTraceId) o;
    return this.id == ddId.id;
  }

  @Override
  public int hashCode() {
    long id = this.id;
    return (int) (id ^ (id >>> 32));
  }

  @Override
  public String toString() {
    String s = this.str;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (s == null) {
      this.str = s = Long.toUnsignedString(this.id);
    }
    return s;
  }

  @Override
  public String toHexString() {
    String hexStr = this.hexStr;
    // This race condition is intentional and benign.
    // The worst that can happen is that an identical value is produced and written into the field.
    if (hexStr == null) {
      this.hexStr = hexStr = LongStringUtils.toHexStringPadded(this.id, 32);
    }
    return hexStr;
  }

  @Override
  public String toHexStringPadded(int size) {
    if (size > 16) {
      return toHexString();
    }
    return LongStringUtils.toHexStringPadded(this.id, size);
  }

  @Override
  public long toLong() {
    return this.id;
  }

  @Override
  public long toHighOrderLong() {
    return 0;
  }
}
