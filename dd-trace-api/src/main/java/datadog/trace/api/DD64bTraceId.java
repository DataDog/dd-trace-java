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
  // Cached zero singleton so create()/from() don't allocate for every zero id. Initialized with
  // this subclass (not in DDTraceId.<clinit>), so it does not reintroduce the init deadlock. It is
  // value-equal to DDTraceId.ZERO.
  private static final DD64bTraceId ZERO_ID = new DD64bTraceId(0, "0");

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
    // Reuse cached singletons rather than allocating: -1 (all bits set) is MAX, 0 is ZERO_ID.
    // ZERO_ID is a DD64bTraceId, not DDTraceId.ZERO (a sibling type), but is value-equal to it;
    // callers detect zero via DDTraceId.isValid() rather than by identity.
    if (id == -1) {
      return MAX;
    } else if (id == 0) {
      return ZERO_ID;
    } else {
      return new DD64bTraceId(id, str);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    // Value-equal to the DDTraceIdConstant backing DDTraceId.ZERO/ONE (also a 64-bit id), so the
    // ZERO/ONE constants keep comparing equal to the equivalent DD64bTraceId as they did when they
    // were themselves DD64bTraceId instances.
    if (o instanceof DD64bTraceId) return this.id == ((DD64bTraceId) o).id;
    if (o instanceof DDTraceIdConstant) return this.id == ((DDTraceIdConstant) o).toLong();
    return false;
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
