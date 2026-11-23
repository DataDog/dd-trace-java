package datadog.trace.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This class is intended to be a drop-in replacement for the hashing portions of java.util.Objects.
 * This class provides more convenience methods for hashing primitives and includes overrides for
 * <code>hash</code> that take many argument lengths to avoid var-args allocation.
 */
public final class LongHashingUtils {
  private LongHashingUtils() {}

  /**
   * Mixes a 64-bit value so that every input bit affects every output bit: the SplitMix64
   * finalizer. Use it to combine hashes whose raw values are correlated, such as the entries of an
   * {@linkplain #unorderedEntryHash unordered hash}; to pick a table slot, a cheaper spread is
   * enough.
   *
   * @param z the value to mix.
   * @return the mixed value.
   */
  public static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /**
   * One entry's share of an order-independent hash of a map: add the shares of all entries (and, to
   * tell sizes apart cheaply, the size) to get a hash that doesn't depend on iteration order.
   *
   * <p>Each entry is mixed before it's added. {@link java.util.Map#hashCode}'s sum of {@code key ^
   * value} collides easily, because XOR lets one entry's bits cancel another's: {@code {a=0, b=0}}
   * and {@code {a=1, b=1}} hash the same under it, but not here. No entry object is needed, so a
   * caller can hash a map's contents, or a map it hasn't built yet, without allocating.
   *
   * @param keyHash the entry key's hash.
   * @param valueHash the entry value's hash, which the caller chooses: for example one that also
   *     tells value types apart.
   * @return the entry's share, to add to the others.
   */
  public static long unorderedEntryHash(long keyHash, long valueHash) {
    return mix(keyHash * 0x9E3779B97F4A7C15L + valueHash);
  }

  /**
   * An order-independent hash of a map's entries, built from {@link #unorderedEntryHash} with the
   * keys' and values' own hash codes. Equal maps hash equally whatever their iteration order.
   *
   * @param map the map; {@code null} hashes like an empty map.
   * @return the hash: {@code 0} for an empty map.
   */
  public static long unorderedHash(@Nullable java.util.Map<?, ?> map) {
    if (map == null || map.isEmpty()) {
      return 0L;
    }
    long hash = map.size();
    for (final java.util.Map.Entry<?, ?> entry : map.entrySet()) {
      hash += unorderedEntryHash(hash(entry.getKey()), hash(entry.getValue()));
    }
    return hash;
  }

  public static final long hash(Object obj) {
    return obj == null ? Long.MIN_VALUE : obj.hashCode();
  }

  public static final long hash(boolean value) {
    return Boolean.hashCode(value);
  }

  public static final long hash(char value) {
    return Character.hashCode(value);
  }

  public static final long hash(byte value) {
    return Byte.hashCode(value);
  }

  public static final long hash(short value) {
    return Short.hashCode(value);
  }

  public static final long hash(int value) {
    return Integer.hashCode(value);
  }

  public static final long hash(long value) {
    return value;
  }

  public static final long hash(float value) {
    return Float.hashCode(value);
  }

  public static final long hash(double value) {
    return Double.doubleToRawLongBits(value);
  }

  public static final long hash(Object obj0, Object obj1) {
    return hash(intHash(obj0), intHash(obj1));
  }

  static final long hash(int hash0, int hash1) {
    return 31L * hash0 + hash1;
  }

  private static final int intHash(@Nullable Object obj) {
    return obj == null ? 0 : obj.hashCode();
  }

  public static final long hash(Object obj0, Object obj1, Object obj2) {
    return hash(intHash(obj0), intHash(obj1), intHash(obj2));
  }

  static final long hash(int hash0, int hash1, int hash2) {
    // DQH - Micro-optimizing, 31L * 31L will constant fold
    // Since there are multiple execution ports for load & store,
    // this will make good use of the core.
    return 31L * 31L * hash0 + 31L * hash1 + hash2;
  }

  public static final long hash(Object obj0, Object obj1, Object obj2, Object obj3) {
    return hash(intHash(obj0), intHash(obj1), intHash(obj2), intHash(obj3));
  }

  static final long hash(int hash0, int hash1, int hash2, int hash3) {
    // DQH - Micro-optimizing, 31L * 31L will constant fold
    // Since there are multiple execution ports for load & store,
    // this will make good use of the core.
    return 31L * 31L * 31L * hash0 + 31L * 31L * hash1 + 31L * hash2 + hash3;
  }

  public static final long hash(Object obj0, Object obj1, Object obj2, Object obj3, Object obj4) {
    return hash(intHash(obj0), intHash(obj1), intHash(obj2), intHash(obj3), intHash(obj4));
  }

  static final long hash(int hash0, int hash1, int hash2, int hash3, int hash4) {
    // DQH - Micro-optimizing, 31L * 31L will constant fold
    // Since there are multiple execution ports for load & store,
    // this will make good use of the core.
    return 31L * 31L * 31L * 31L * hash0
        + 31L * 31L * 31L * hash1
        + 31L * 31L * hash2
        + 31L * hash3
        + hash4;
  }

  @Deprecated
  public static final long hash(int[] hashes) {
    long result = 0;
    for (int hash : hashes) {
      result = addToHash(result, hash);
    }
    return result;
  }

  public static final long addToHash(long hash, int value) {
    return 31L * hash + value;
  }

  public static final long addToHash(long hash, @Nullable Object obj) {
    return addToHash(hash, intHash(obj));
  }

  public static final long addToHash(long hash, boolean value) {
    return addToHash(hash, Boolean.hashCode(value));
  }

  public static final long addToHash(long hash, char value) {
    return addToHash(hash, Character.hashCode(value));
  }

  public static final long addToHash(long hash, byte value) {
    return addToHash(hash, Byte.hashCode(value));
  }

  public static final long addToHash(long hash, short value) {
    return addToHash(hash, Short.hashCode(value));
  }

  public static final long addToHash(long hash, long value) {
    return addToHash(hash, Long.hashCode(value));
  }

  public static final long addToHash(long hash, float value) {
    return addToHash(hash, Float.hashCode(value));
  }

  public static final long addToHash(long hash, double value) {
    return addToHash(hash, Double.hashCode(value));
  }

  /** Folds {@code arr[0..len)}; the array must be non-null, but its elements may be null. */
  public static final long addToHash(long hash, @Nonnull Object[] arr, int len) {
    for (int i = 0; i < len; i++) {
      hash = addToHash(hash, arr[i]);
    }
    return hash;
  }

  public static final long addToHash(long hash, @Nonnull Object[] arr) {
    return addToHash(hash, arr, arr.length);
  }

  public static final long hash(Iterable<?> objs) {
    long result = 0;
    for (Object obj : objs) {
      result = addToHash(result, obj);
    }
    return result;
  }

  /**
   * Calling this var-arg version can result in large amounts of allocation (see HashingBenchmark)
   * Rather than calliing this method, add another override of hash that handles a larger number of
   * arguments or use calls to addToHash.
   */
  @Deprecated
  public static final long hash(Object[] objs) {
    long result = 0;
    for (Object obj : objs) {
      result = addToHash(result, obj);
    }
    return result;
  }
}
