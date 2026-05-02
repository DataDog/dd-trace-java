package datadog.trace.util;

/**
 * This class is intended to be a drop-in replacement for the hashing portions of java.util.Objects.
 * This class provides more convenience methods for hashing primitives and includes overrides for
 * <code>hash</code> that take many argument lengths to avoid var-args allocation.
 */
public final class HashingUtils {
  private HashingUtils() {}

  public static final int hashCode(Object obj) {
    return obj != null ? obj.hashCode() : 0;
  }

  public static final int hash(boolean value) {
    return Boolean.hashCode(value);
  }

  public static final int hash(char value) {
    return Character.hashCode(value);
  }

  public static final int hash(byte value) {
    return Byte.hashCode(value);
  }

  public static final int hash(short value) {
    return Short.hashCode(value);
  }

  public static final int hash(int value) {
    return Integer.hashCode(value);
  }

  public static final int hash(long value) {
    return Long.hashCode(value);
  }

  public static final int hash(float value) {
    return Float.hashCode(value);
  }

  public static final int hash(double value) {
    return Double.hashCode(value);
  }

  public static final int hash(Object obj) {
    return obj != null ? obj.hashCode() : 0;
  }

  public static final int hash(Object obj0, Object obj1) {
    return hash(hash(obj0), hash(obj1));
  }

  public static final int hash(int hash0, int hash1) {
    return 31 * hash0 + hash1;
  }

  public static final int hash(Object obj0, Object obj1, Object obj2) {
    return hash(hashCode(obj0), hashCode(obj1), hashCode(obj2));
  }

  public static final int hash(int hash0, int hash1, int hash2) {
    // DQH - Micro-optimizing, 31 * 31 will constant fold
    // Since there are multiple execution ports for load & store,
    // this will make good use of the core.
    return 31 * 31 * hash0 + 31 * hash1 + hash2;
  }

  public static final int hash(Object obj0, Object obj1, Object obj2, Object obj3) {
    return hash(hashCode(obj0), hashCode(obj1), hashCode(obj2), hashCode(obj3));
  }

  public static final int hash(int hash0, int hash1, int hash2, int hash3) {
    // DQH - Micro-optimizing, 31 * 31 will constant fold
    // Since there are multiple execution ports for load & store,
    // this will make good use of the core.
    return 31 * 31 * 31 * hash0 + 31 * 31 * hash1 + 31 * hash2 + hash3;
  }

  public static final int hash(Object obj0, Object obj1, Object obj2, Object obj3, Object obj4) {
    return hash(hashCode(obj0), hashCode(obj1), hashCode(obj2), hashCode(obj3));
  }

  public static final int hash(int hash0, int hash1, int hash2, int hash3, int hash4) {
    // DQH - Micro-optimizing, 31 * 31 will constant fold
    // Since there are multiple execution ports for load & store,
    // this will make good use of the core.
    return 31 * 31 * 31 * 31 * hash0 + 31 * 31 * 31 * hash1 + 31 * 31 * hash2 + 31 * hash3 + hash4;
  }

  @Deprecated
  public static final int hash(int[] hashes) {
    int result = 0;
    for (int hash : hashes) {
      result = addToHash(result, hash);
    }
    return result;
  }

  public static final int addToHash(int hash, int value) {
    return 31 * hash + value;
  }

  public static final int addToHash(int hash, Object obj) {
    return addToHash(hash, hashCode(obj));
  }

  public static final int addToHash(int hash, boolean value) {
    return addToHash(hash, Boolean.hashCode(value));
  }

  public static final int addToHash(int hash, char value) {
    return addToHash(hash, Character.hashCode(value));
  }

  public static final int addToHash(int hash, byte value) {
    return addToHash(hash, Byte.hashCode(value));
  }

  public static final int addToHash(int hash, short value) {
    return addToHash(hash, Short.hashCode(value));
  }

  public static final int addToHash(int hash, long value) {
    return addToHash(hash, Long.hashCode(value));
  }

  public static final int addToHash(int hash, float value) {
    return addToHash(hash, Float.hashCode(value));
  }

  public static final int addToHash(int hash, double value) {
    return addToHash(hash, Double.hashCode(value));
  }

  public static final int hash(Iterable<?> objs) {
    int result = 0;
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
  public static final int hash(Object[] objs) {
    int result = 0;
    for (Object obj : objs) {
      result = addToHash(result, obj);
    }
    return result;
  }
}
