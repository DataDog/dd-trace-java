package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TagValueConversionsTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void boolean_(boolean value) {
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
  public void int_(int value) {
	Integer box = Integer.valueOf(value);
	
	assertEquals(TagMap.EntryReader.INT, TagValueConversions.typeOf(box));
	assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.INT));
	assertTrue(TagValueConversions.isNumericPrimitive(box));
	assertTrue(TagValueConversions.isNumber(box));
	assertFalse(TagValueConversions.isObject(box));
	
	assertEquals(value, TagValueConversions.toInt(box));
	assertEquals((long)value, TagValueConversions.toLong(box));
	assertEquals((float)value, TagValueConversions.toFloat(box));
	assertEquals((double)value, TagValueConversions.toDouble(box));
	
	assertEquals(value != 0, TagValueConversions.toBoolean(box));
	assertEquals(Integer.toString(value), TagValueConversions.toString(box));
  }
  
  @ParameterizedTest
  @ValueSource(bytes = {Byte.MIN_VALUE, -32, -1, 0, 1, 32, Byte.MAX_VALUE})
  public void byte_(byte value) {
	Byte box = Byte.valueOf(value);
	
	assertEquals(TagMap.EntryReader.INT, TagValueConversions.typeOf(box));
	assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.INT));
	assertTrue(TagValueConversions.isNumericPrimitive(box));
	assertTrue(TagValueConversions.isNumber(box));
	assertFalse(TagValueConversions.isObject(box));
	
	assertEquals((int)value, TagValueConversions.toInt(box));
	assertEquals((long)value, TagValueConversions.toLong(box));
	assertEquals((float)value, TagValueConversions.toFloat(box));
	assertEquals((double)value, TagValueConversions.toDouble(box));
	
	assertEquals(value != 0, TagValueConversions.toBoolean(box));
	assertEquals(Byte.toString(value), TagValueConversions.toString(box));
  }
  
  @ParameterizedTest
  @ValueSource(shorts = {Short.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Short.MAX_VALUE})
  public void short_(short value) {
	Short box = Short.valueOf(value);
	
	assertEquals(TagMap.EntryReader.INT, TagValueConversions.typeOf(box));
	assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.INT));
	assertTrue(TagValueConversions.isNumericPrimitive(box));
	assertTrue(TagValueConversions.isNumber(box));
	assertFalse(TagValueConversions.isObject(box));
	
	assertEquals((int)value, TagValueConversions.toInt(box));
	assertEquals((long)value, TagValueConversions.toLong(box));
	assertEquals((float)value, TagValueConversions.toFloat(box));
	assertEquals((double)value, TagValueConversions.toDouble(box));
	
	assertEquals(value != 0, TagValueConversions.toBoolean(box));
	assertEquals(Short.toString(value), TagValueConversions.toString(box));
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
  public void long_(long value) {
	Long box = Long.valueOf(value);
	
	assertEquals(TagMap.EntryReader.LONG, TagValueConversions.typeOf(box));
	assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.LONG));
	assertTrue(TagValueConversions.isNumericPrimitive(box));
	assertTrue(TagValueConversions.isNumber(box));
	assertFalse(TagValueConversions.isObject(box));

	assertEquals(value, TagValueConversions.toLong(box));
	assertEquals((int)value, TagValueConversions.toInt(box));
	assertEquals((float)value, TagValueConversions.toFloat(box));
	assertEquals((double)value, TagValueConversions.toDouble(box));
	
	assertEquals(value != 0L, TagValueConversions.toBoolean(box));
	assertEquals(Long.toString(value), TagValueConversions.toString(box));
  }
  
  @ParameterizedTest
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void float_(float value) {
	Float box = Float.valueOf(value);
	
	assertEquals(TagMap.EntryReader.FLOAT, TagValueConversions.typeOf(box));
	assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.FLOAT));
	assertTrue(TagValueConversions.isNumericPrimitive(box));
	assertTrue(TagValueConversions.isNumber(box));
	assertFalse(TagValueConversions.isObject(box));

	assertEquals(value, TagValueConversions.toFloat(box));
	assertEquals((int)value, TagValueConversions.toInt(box));
	assertEquals((long)value, TagValueConversions.toLong(box));
	assertEquals((double)value, TagValueConversions.toDouble(box));
	
	assertEquals(value != 0F, TagValueConversions.toBoolean(box));
	assertEquals(Float.toString(value), TagValueConversions.toString(box));
  }
  
  @ParameterizedTest
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void double_(double value) {
	Double box = Double.valueOf(value);
	
	assertEquals(TagMap.EntryReader.DOUBLE, TagValueConversions.typeOf(box));
	assertTrue(TagValueConversions.isA(box, TagMap.EntryReader.DOUBLE));
	assertTrue(TagValueConversions.isNumericPrimitive(box));
	assertTrue(TagValueConversions.isNumber(box));
	assertFalse(TagValueConversions.isObject(box));

	assertEquals(value, TagValueConversions.toDouble(box));
	assertEquals((int)value, TagValueConversions.toInt(box));
	assertEquals((long)value, TagValueConversions.toLong(box));
	assertEquals((float)value, TagValueConversions.toFloat(box));
	
	assertEquals(value != 0D, TagValueConversions.toBoolean(box));
	assertEquals(Double.toString(value), TagValueConversions.toString(box));
  }
}
