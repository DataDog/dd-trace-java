package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class HashingUtilsTest {
  @Test
  public void hashCode_() {
    assertEquals("bar".hashCode(), HashingUtils.hashCode("bar"));
  }

  @Test
  public void hashCodeNull() {
    assertEquals(0, HashingUtils.hash((Object) null));
  }

  @Test
  public void hash1() {
    assertEquals("foo".hashCode(), HashingUtils.hashCode("foo"));
  }

  @Test
  public void hash1Null() {
    assertEquals(0, HashingUtils.hashCode(null));
  }

  @Test
  public void hash2() {
    String str0 = "foo";
    String str1 = "bar";

    assertNotEquals(0, HashingUtils.hash(str0, str1));

    String clone0 = clone(str0);
    String clone1 = clone(str1);

    assertEquals(HashingUtils.hash(str0, str1), HashingUtils.hash(clone0, clone1));
  }

  @Test
  public void hash2Null() {
    assertEquals(0, HashingUtils.hash(null, null));
  }

  @Test
  public void hash3() {
    String str0 = "foo";
    String str2 = "quux";
    String str1 = "bar";

    assertNotEquals(0, HashingUtils.hash(str0, str1, str2));

    String clone0 = clone(str0);
    String clone1 = clone(str1);
    String clone2 = clone(str2);

    assertEquals(HashingUtils.hash(str0, str1, str2), HashingUtils.hash(clone0, clone1, clone2));
  }

  @Test
  public void hash3Null() {
    assertEquals(0, HashingUtils.hash(null, null, null));
  }

  @Test
  public void hash4() {
    String str0 = "foo";
    String str1 = "bar";
    String str2 = "quux";
    String str3 = "foobar";

    assertNotEquals(0, HashingUtils.hash(str0, str1, str2, str3));

    String clone0 = clone(str0);
    String clone1 = clone(str1);
    String clone2 = clone(str2);
    String clone3 = clone(str3);

    assertEquals(
        HashingUtils.hash(str0, str1, str2, str3),
        HashingUtils.hash(clone0, clone1, clone2, clone3));
  }

  @Test
  public void hash4Null() {
    assertEquals(0, HashingUtils.hash(null, null, null, null));
  }

  @Test
  public void hash5() {
    String str0 = "foo";
    String str1 = "bar";
    String str2 = "quux";
    String str3 = "foobar";
    String str4 = "hello";

    assertNotEquals(0, HashingUtils.hash(str0, str1, str2, str3));

    String clone0 = clone(str0);
    String clone1 = clone(str1);
    String clone2 = clone(str2);
    String clone3 = clone(str3);
    String clone4 = clone(str4);

    assertEquals(
        HashingUtils.hash(str0, str1, str2, str3, str4),
        HashingUtils.hash(clone0, clone1, clone2, clone3, clone4));
  }

  @Test
  public void hash5Null() {
    assertEquals(0, HashingUtils.hash(null, null, null, null, null));
  }

  @Test
  public void hashArrayAndIterable() {
    String str0 = "foo";
    String str1 = "bar";
    String str2 = "quux";
    String str3 = "foobar";
    String str4 = "foobaz";
    String str5 = "hello";
    String str6 = "world";

    Object[] array = new Object[] {str0, str1, str2, str3, str4, str5, str6};

    int hashArray = HashingUtils.hash(array);
    assertNotEquals(0, hashArray);

    int hashIterable = HashingUtils.hash(Arrays.asList(array));
    assertNotEquals(0, hashIterable);

    assertEquals(hashArray, hashIterable);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void booleans(boolean value) {
    assertEquals(Boolean.hashCode(value), HashingUtils.hash(value));
    assertEquals(Boolean.hashCode(value), HashingUtils.addToHash(0, value));
  }

  @ParameterizedTest
  @ValueSource(chars = {Character.MIN_VALUE, 'a', 'A', '\0', 'z', 'Z', Character.MAX_VALUE})
  public void chars(char value) {
    assertEquals(Character.hashCode(value), HashingUtils.hash(value));
    assertEquals(Character.hashCode(value), HashingUtils.addToHash(0, value));
  }

  @ParameterizedTest
  @ValueSource(bytes = {Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE})
  public void bytes(byte value) {
    assertEquals(Byte.hashCode(value), HashingUtils.hash(value));
    assertEquals(Byte.hashCode(value), HashingUtils.addToHash(0, value));
  }

  @ParameterizedTest
  @ValueSource(
      shorts = {Short.MIN_VALUE, Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE, Short.MAX_VALUE})
  public void shorts(short value) {
    assertEquals(Short.hashCode(value), HashingUtils.hash(value));
    assertEquals(Short.hashCode(value), HashingUtils.addToHash(0, value));
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        Integer.MIN_VALUE,
        Short.MIN_VALUE,
        Byte.MIN_VALUE,
        -1,
        0,
        1,
        Byte.MAX_VALUE,
        Short.MAX_VALUE,
        Integer.MAX_VALUE
      })
  public void ints(int value) {
    assertEquals(Integer.hashCode(value), HashingUtils.hash(value));
    assertEquals(Integer.hashCode(value), HashingUtils.addToHash(0, value));
  }

  @ParameterizedTest
  @ValueSource(
      longs = {
        Long.MIN_VALUE,
        Integer.MIN_VALUE,
        Short.MIN_VALUE,
        Byte.MIN_VALUE,
        -1,
        0,
        1,
        Byte.MAX_VALUE,
        Short.MAX_VALUE,
        Integer.MAX_VALUE,
        Long.MAX_VALUE
      })
  public void longs(long value) {
    assertEquals(Long.hashCode(value), HashingUtils.hash(value));
    assertEquals(Long.hashCode(value), HashingUtils.addToHash(0, value));
  }

  @ParameterizedTest
  @ValueSource(floats = {Float.MIN_VALUE, -1, 0, 1, 2.71828f, 3.1415f, Float.MAX_VALUE})
  public void floats(float value) {
    assertEquals(Float.hashCode(value), HashingUtils.hash(value));
    assertEquals(Float.hashCode(value), HashingUtils.addToHash(0, value));
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {
        Double.MIN_VALUE,
        Float.MIN_VALUE,
        -1,
        0,
        1,
        2.71828,
        3.1415,
        Float.MAX_VALUE,
        Double.MAX_VALUE
      })
  public void floats(double value) {
    assertEquals(Double.hashCode(value), HashingUtils.hash(value));
    assertEquals(Double.hashCode(value), HashingUtils.addToHash(0, value));
  }

  static final String clone(String str) {
    return new String(str);
  }

  static final String[] deepClone(String[] strings) {
    String[] clones = new String[strings.length];
    for (int i = 0; i < strings.length; ++i) {
      clones[i] = clone(strings[i]);
    }
    return clones;
  }
}
