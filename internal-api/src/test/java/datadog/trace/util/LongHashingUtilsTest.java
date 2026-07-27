package datadog.trace.util;

import static datadog.trace.util.LongHashingUtils.addToHash;
import static datadog.trace.util.LongHashingUtils.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class LongHashingUtilsTest {

  // ----- single-value overloads -----

  @Test
  void hashOfObjectReturnsHashCodeOrSentinelForNull() {
    Object o = new Object();
    assertEquals(o.hashCode(), hash(o));
    assertEquals(Long.MIN_VALUE, hash((Object) null));
  }

  @Test
  void primitiveOverloadsMatchBoxedHashCodes() {
    assertEquals(Boolean.hashCode(true), hash(true));
    assertEquals(Boolean.hashCode(false), hash(false));
    assertEquals(Character.hashCode('x'), hash('x'));
    assertEquals(Byte.hashCode((byte) 42), hash((byte) 42));
    assertEquals(Short.hashCode((short) -7), hash((short) -7));
    assertEquals(Integer.hashCode(123456), hash(123456));
    assertEquals(123456L, hash(123456L));
    assertEquals(Float.hashCode(3.14f), hash(3.14f));
    assertEquals(Double.doubleToRawLongBits(2.71828), hash(2.71828));
  }

  // ----- multi-arg Object overloads vs chained addToHash -----

  @Test
  void twoArgHashMatchesChainedAddToHash() {
    Object a = "alpha";
    Object b = 42;
    assertEquals(addToHash(addToHash(0L, a), b), hash(a, b));
  }

  @Test
  void threeArgHashMatchesChainedAddToHash() {
    Object a = "alpha";
    Object b = 42;
    Object c = true;
    assertEquals(addToHash(addToHash(addToHash(0L, a), b), c), hash(a, b, c));
  }

  @Test
  void fourArgHashMatchesChainedAddToHash() {
    Object a = "alpha";
    Object b = 42;
    Object c = true;
    Object d = 3.14;
    assertEquals(addToHash(addToHash(addToHash(addToHash(0L, a), b), c), d), hash(a, b, c, d));
  }

  @Test
  void fiveArgHashMatchesChainedAddToHash() {
    Object a = "alpha";
    Object b = 42;
    Object c = true;
    Object d = 3.14;
    Object e = 'q';
    assertEquals(
        addToHash(addToHash(addToHash(addToHash(addToHash(0L, a), b), c), d), e),
        hash(a, b, c, d, e));
  }

  @Test
  void multiArgHashHandlesNullsConsistentlyWithChainedAddToHash() {
    assertEquals(addToHash(addToHash(0L, (Object) null), "x"), hash(null, "x"));
    assertEquals(
        addToHash(addToHash(addToHash(0L, "x"), (Object) null), "y"), hash("x", null, "y"));
  }

  @Test
  void differentInputsProduceDifferentHashes() {
    // Sanity: ordering matters, and distinct values produce distinct results in general.
    assertNotEquals(hash("a", "b"), hash("b", "a"));
    assertNotEquals(hash("a", "b", "c"), hash("a", "c", "b"));
  }

  // ----- addToHash primitive overloads -----

  @Test
  void addToHashPrimitivesMatchObjectVersion() {
    long seed = 100L;
    assertEquals(addToHash(seed, Boolean.hashCode(true)), addToHash(seed, true));
    assertEquals(addToHash(seed, Character.hashCode('z')), addToHash(seed, 'z'));
    assertEquals(addToHash(seed, Byte.hashCode((byte) 9)), addToHash(seed, (byte) 9));
    assertEquals(addToHash(seed, Short.hashCode((short) 5)), addToHash(seed, (short) 5));
    assertEquals(addToHash(seed, Long.hashCode(999_999L)), addToHash(seed, 999_999L));
    assertEquals(addToHash(seed, Float.hashCode(1.5f)), addToHash(seed, 1.5f));
    assertEquals(addToHash(seed, Double.hashCode(2.5d)), addToHash(seed, 2.5d));
  }

  @Test
  void addToHashIsLinearAcrossSteps() {
    // 31*h + v formula -- verify by accumulating an explicit sequence.
    long expected = 0L;
    for (int v : new int[] {1, 2, 3, 4, 5}) {
      expected = 31L * expected + v;
    }
    long actual = 0L;
    for (int v : new int[] {1, 2, 3, 4, 5}) {
      actual = addToHash(actual, v);
    }
    assertEquals(expected, actual);
  }

  // ----- iterable / array versions -----

  @Test
  void hashIterableMatchesChainedAddToHash() {
    Iterable<Object> values = Arrays.asList("a", 1, true, null);
    long expected = 0L;
    for (Object o : values) {
      expected = addToHash(expected, o);
    }
    assertEquals(expected, hash(values));
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedIntArrayHashMatchesChainedAddToHash() {
    int[] hashes = new int[] {7, 13, 31, 1024};
    long expected = 0L;
    for (int h : hashes) {
      expected = addToHash(expected, h);
    }
    assertEquals(expected, hash(hashes));
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedObjectArrayHashMatchesChainedAddToHash() {
    Object[] objs = new Object[] {"alpha", 7, null, true};
    long expected = 0L;
    for (Object o : objs) {
      expected = addToHash(expected, o);
    }
    assertEquals(expected, hash(objs));
  }

  @Test
  void addToHashArrayFoldsFromSeedLikeChainedAddToHash() {
    Object[] objs = new Object[] {"alpha", 7, null, true};

    // Full-array overload folds every element onto the seed, matching an explicit chain.
    long fromZero = 0L;
    for (Object o : objs) {
      fromZero = addToHash(fromZero, o);
    }
    assertEquals(fromZero, addToHash(0L, objs));

    // A non-zero seed carries through, so the result differs from the zero-seed fold.
    long fromSeed = 100L;
    for (Object o : objs) {
      fromSeed = addToHash(fromSeed, o);
    }
    assertEquals(fromSeed, addToHash(100L, objs));
    assertNotEquals(addToHash(0L, objs), addToHash(100L, objs));
  }

  @Test
  void addToHashArrayRespectsLen() {
    Object[] objs = new Object[] {"alpha", 7, null, true};

    // The len override folds only the first len elements.
    long firstTwo = addToHash(addToHash(0L, objs[0]), objs[1]);
    assertEquals(firstTwo, addToHash(0L, objs, 2));
    assertNotEquals(addToHash(0L, objs), addToHash(0L, objs, 2));

    // len==0 never enters the loop and returns the seed unchanged.
    assertEquals(42L, addToHash(42L, objs, 0));
  }

  // ----- intHash null behavior is observable via multi-arg overloads -----

  @Test
  void multiArgHashTreatsNullAsZero() {
    // hash(Object,Object) feeds intHash(...) which returns 0 for null.
    // Verify: hash(null, "x") == 31L*0 + "x".hashCode()
    int xHash = Objects.hashCode("x");
    assertEquals(31L * 0 + xHash, hash(null, "x"));
  }
}
