package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class TagValueConversionsTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void booleanConversions(boolean value) {
    Boolean box = Boolean.valueOf(value);

    assertEquals(TagMap.EntryReader.BOOLEAN, TagValueConversions.typeOf(box));
    assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.BOOLEAN));
    assertFalse(TagValueConversions.isNumericPrimitive(box));
    assertFalse(TagValueConversions.isNumber(box));
    assertFalse(TagValueConversions.isObject(box));

    assertEquals(value, TagValueConversions.toBoolean(box));
    assertEquals(value ? 1 : 0, TagValueConversions.toInt(box));
    assertEquals(value ? 1L : 0L, TagValueConversions.toLong(box));
    assertEquals(value ? 1F : 0F, TagValueConversions.toFloat(box));
    assertEquals(value ? 1D : 0D, TagValueConversions.toDouble(box));

    assertEquals(Boolean.toString(value), TagValueConversions.toString(box));
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void intConversions(int value) {
    Integer box = Integer.valueOf(value);

    assertEquals(TagMap.EntryReader.INT, TagValueConversions.typeOf(box));
    assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.INT));
    assertTrue(TagValueConversions.isNumericPrimitive(box));
    assertTrue(TagValueConversions.isNumber(box));
    assertFalse(TagValueConversions.isObject(box));

    assertEquals(value, TagValueConversions.toInt(box));
    assertEquals((long) value, TagValueConversions.toLong(box));
    assertEquals((float) value, TagValueConversions.toFloat(box));
    assertEquals((double) value, TagValueConversions.toDouble(box));

    assertEquals(value != 0, TagValueConversions.toBoolean(box));
    assertEquals(Integer.toString(value), TagValueConversions.toString(box));
  }

  // Entry types only Boolean, Integer, Long, Float and Double; a Byte or Short is an OBJECT, which
  // coerces to true (present) and to 0, whatever its value.
  @ParameterizedTest
  @ValueSource(bytes = {Byte.MIN_VALUE, -32, -1, 0, 1, 32, Byte.MAX_VALUE})
  public void byteConversions(byte value) {
    assertObjectConversions(Byte.valueOf(value));
  }

  @ParameterizedTest
  @ValueSource(shorts = {Short.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Short.MAX_VALUE})
  public void shortConversions(short value) {
    assertObjectConversions(Short.valueOf(value));
  }

  @Test
  public void otherObjectConversions() {
    assertObjectConversions("GET");
    assertObjectConversions("");
    assertObjectConversions(new BigDecimal("12"));
    assertObjectConversions(BigDecimal.ZERO);
    assertObjectConversions(BigInteger.TEN);
    assertObjectConversions('x');
  }

  private static void assertObjectConversions(Object box) {
    assertEquals(TagMap.EntryReader.OBJECT, TagValueConversions.typeOf(box));
    assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.OBJECT));
    assertFalse(TagValueConversions.isA(box, TagMap.EntryReader.INT));
    assertTrue(TagValueConversions.isObject(box));
    assertFalse(TagValueConversions.isNumericPrimitive(box));
    assertEquals(box instanceof Number, TagValueConversions.isNumber(box));

    assertTrue(TagValueConversions.toBoolean(box));
    assertEquals(0, TagValueConversions.toInt(box));
    assertEquals(0L, TagValueConversions.toLong(box));
    assertEquals(0F, TagValueConversions.toFloat(box));
    assertEquals(0D, TagValueConversions.toDouble(box));
    assertEquals(box.toString(), TagValueConversions.toString(box));
  }

  /** Every conversion reports what an Entry of the same value does. */
  @ParameterizedTest
  @MethodSource("values")
  public void agreesWithEntry(Object value) {
    TagMap.Entry entry = TagMap.Entry.newAnyEntry("tag", value);
    String what = value.getClass().getSimpleName() + " " + value;

    assertEquals(entry.type(), TagValueConversions.typeOf(value), what);
    for (byte type :
        new byte[] {
          TagMap.EntryReader.OBJECT,
          TagMap.EntryReader.BOOLEAN,
          TagMap.EntryReader.INT,
          TagMap.EntryReader.LONG,
          TagMap.EntryReader.FLOAT,
          TagMap.EntryReader.DOUBLE
        }) {
      assertEquals(entry.is(type), TagValueConversions.isA(value, type), what + " is " + type);
    }
    assertEquals(entry.isObject(), TagValueConversions.isObject(value), what);
    assertEquals(entry.isNumber(), TagValueConversions.isNumber(value), what);
    assertEquals(entry.isNumericPrimitive(), TagValueConversions.isNumericPrimitive(value), what);
    assertEquals(entry.booleanValue(), TagValueConversions.toBoolean(value), what);
    assertEquals(entry.intValue(), TagValueConversions.toInt(value), what);
    assertEquals(entry.longValue(), TagValueConversions.toLong(value), what);
    assertEquals(entry.floatValue(), TagValueConversions.toFloat(value), what);
    assertEquals(entry.doubleValue(), TagValueConversions.toDouble(value), what);
    assertEquals(entry.stringValue(), TagValueConversions.toString(value), what);
  }

  static Stream<Object> values() {
    return Stream.of(
        true,
        false,
        0,
        42,
        -7,
        Integer.MIN_VALUE,
        0L,
        1L << 40,
        Long.MIN_VALUE,
        0F,
        1.5F,
        -0F,
        Float.NaN,
        0D,
        -2.75D,
        Double.NaN,
        1e300D,
        (short) 5,
        (short) 0,
        (byte) 7,
        'x',
        "GET",
        "",
        "true",
        "12",
        new BigDecimal("12"),
        BigDecimal.ZERO,
        BigInteger.TEN,
        new AtomicLong(3),
        new StringBuilder("sb"));
  }

  @ParameterizedTest
  @ValueSource(
      longs = {
        Long.MIN_VALUE,
        Integer.MIN_VALUE,
        -1_048_576L,
        -256L,
        -128L,
        -1L,
        0L,
        1L,
        128L,
        256L,
        1_048_576L,
        Integer.MAX_VALUE,
        Long.MAX_VALUE
      })
  public void longConversions(long value) {
    Long box = Long.valueOf(value);

    assertEquals(TagMap.EntryReader.LONG, TagValueConversions.typeOf(box));
    assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.LONG));
    assertTrue(TagValueConversions.isNumericPrimitive(box));
    assertTrue(TagValueConversions.isNumber(box));
    assertFalse(TagValueConversions.isObject(box));

    assertEquals(value, TagValueConversions.toLong(box));
    assertEquals((int) value, TagValueConversions.toInt(box));
    assertEquals((float) value, TagValueConversions.toFloat(box));
    assertEquals((double) value, TagValueConversions.toDouble(box));

    assertEquals(value != 0L, TagValueConversions.toBoolean(box));
    assertEquals(Long.toString(value), TagValueConversions.toString(box));
  }

  @ParameterizedTest
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void floatConversions(float value) {
    Float box = Float.valueOf(value);

    assertEquals(TagMap.EntryReader.FLOAT, TagValueConversions.typeOf(box));
    assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.FLOAT));
    assertTrue(TagValueConversions.isNumericPrimitive(box));
    assertTrue(TagValueConversions.isNumber(box));
    assertFalse(TagValueConversions.isObject(box));

    assertEquals(value, TagValueConversions.toFloat(box));
    assertEquals((int) value, TagValueConversions.toInt(box));
    assertEquals((long) value, TagValueConversions.toLong(box));
    assertEquals((double) value, TagValueConversions.toDouble(box));

    assertEquals(value != 0F, TagValueConversions.toBoolean(box));
    assertEquals(Float.toString(value), TagValueConversions.toString(box));
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void doubleConversions(double value) {
    Double box = Double.valueOf(value);

    assertEquals(TagMap.EntryReader.DOUBLE, TagValueConversions.typeOf(box));
    assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.DOUBLE));
    assertTrue(TagValueConversions.isNumericPrimitive(box));
    assertTrue(TagValueConversions.isNumber(box));
    assertFalse(TagValueConversions.isObject(box));

    assertEquals(value, TagValueConversions.toDouble(box));
    assertEquals((int) value, TagValueConversions.toInt(box));
    assertEquals((long) value, TagValueConversions.toLong(box));
    assertEquals((float) value, TagValueConversions.toFloat(box));

    assertEquals(value != 0D, TagValueConversions.toBoolean(box));
    assertEquals(Double.toString(value), TagValueConversions.toString(box));
  }
}
