package datadog.trace.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This class is intended to be a drop-in replacement for the hashing portions of java.util.Objects.
 * This class provides more convenience methods for hashing primitives and includes overrides for
 * <code>hash</code> that take many argument lengths to avoid var-args allocation.
 */
public final class HashingUtils {
  private HashingUtils() {}

  public static final int hashCode(@Nullable Object obj) {
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
    return hash(hashCode(obj0), hashCode(obj1), hashCode(obj2), hashCode(obj3), hashCode(obj4));
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

  public static final int addToHash(int hash, @Nullable Object obj) {
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

  /** Folds {@code arr[0..len)}; the array must be non-null, but its elements may be null. */
  public static final int addToHash(int hash, @Nonnull Object[] arr, int len) {
    for (int i = 0; i < len; i++) {
      hash = addToHash(hash, arr[i]);
    }
    return hash;
  }

  public static final int addToHash(int hash, @Nonnull Object[] arr) {
    return addToHash(hash, arr, arr.length);
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

  /**
   * Spreads a hash code across all 32 bits, for a table that takes its slot from the low bits. A
   * golden-ratio (Fibonacci) multiply mixes the low input bits into the high bits of the product,
   * and the fold brings those back down. So keys whose hash codes differ only slightly, such as
   * sequential names like {@code key-00001} and {@code key-00002}, land far apart instead of in
   * adjacent slots that grow into long probe runs. The multiplier is odd, so the multiply loses no
   * bits.
   *
   * <p>One 32-bit multiply, a shift and an xor: cheap enough for lookups of keys that are already
   * well spread. For a 64-bit composite hash, use {@link LongHashingUtils#hashSpread(long)}.
   *
   * @param hash a hash code.
   * @return the spread hash: mask it for a slot, or store it.
   */
  public static int hashSpread(int hash) {
    final int z = hash * 0x9E3779B9; // 2^32 / golden ratio
    return z ^ (z >>> 15);
  }

  /**
   * A {@code hashCode} consistent with {@link String#equalsIgnoreCase}: any two strings that are
   * equal ignoring case produce the same value. Same polynomial as {@link String#hashCode} but over
   * the case-folded characters, so it never allocates (no {@code toLowerCase} copy).
   *
   * <p>Uses the same two-way fold {@code String.equalsIgnoreCase} / {@code
   * String.regionMatches(ignoreCase)} use ({@code toLowerCase(toUpperCase(c))}), so the two stay
   * consistent for all inputs, not just ASCII — pairing a one-way fold here with {@code
   * equalsIgnoreCase} would risk silent false misses on the Unicode characters where they diverge.
   *
   * <p>Folds by code point, not by {@code char} (UTF-16 unit). On JDK 9+, {@code equalsIgnoreCase}
   * itself reconstructs supplementary code points before folding (e.g. it treats U+10400 and
   * U+10428 as equal), so a per-{@code char} fold would make this hash inconsistent with it for
   * such pairs -- a supplementary-plane analog of the same silent-false-miss risk called out above.
   * A per-{@code char} fold matched {@code equalsIgnoreCase} only on JDK 8, where it did not
   * reconstruct code points; folding by code point keeps the two consistent on every supported JDK.
   * (On JDK 8, where {@code equalsIgnoreCase} still treats such pairs as distinct, folding them
   * together here is merely an extra, harmless collision -- consistency only needs to hold in the
   * equal-hash direction, not the reverse.)
   */
  public static int caseInsensitiveHashCode(String s) {
    int h = 0;
    final int len = s.length();
    for (int i = 0; i < len; ) {
      final char c = s.charAt(i);
      if (c < 0x80) {
        // ASCII fast path: case folding is exactly A-Z -> a-z, and every ASCII char is its own
        // code point, so this is equivalent to (and cheaper than) the code-point fold below.
        h = 31 * h + ((c >= 'A' && c <= 'Z') ? (c + 32) : c);
        ++i;
        continue;
      }
      final int cp = s.codePointAt(i);
      final int folded = Character.toLowerCase(Character.toUpperCase(cp));
      if (Character.isSupplementaryCodePoint(folded)) {
        // Fold back into the two chars equalsIgnoreCase itself compares, so the polynomial stays
        // over the same "characters" for both single- and multi-char inputs.
        h = 31 * h + Character.highSurrogate(folded);
        h = 31 * h + Character.lowSurrogate(folded);
      } else {
        h = 31 * h + folded;
      }
      i += Character.charCount(cp);
    }
    return h;
  }
}
