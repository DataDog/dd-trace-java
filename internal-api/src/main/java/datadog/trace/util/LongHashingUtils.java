package datadog.trace.util;

/**
 * This class is intended to be a drop-in replacement for the hashing portions of java.util.Objects.
 * This class provides more convenience methods for hashing primitives and includes overrides for
 * <code>hash</code> that take many argument lengths to avoid var-args allocation.
 */
public final class LongHashingUtils {
  private LongHashingUtils() {}

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

  private static final int intHash(Object obj) {
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

  public static final long addToHash(long hash, Object obj) {
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

  public static final long addToHash(long hash, Object[] arr, int len) {
    for (int i = 0; i < len; i++) {
      hash = addToHash(hash, arr[i]);
    }
    return hash;
  }

  public static final long addToHash(long hash, Object[] arr) {
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
