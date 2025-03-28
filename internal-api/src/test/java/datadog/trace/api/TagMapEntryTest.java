package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMap.Entry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Test;

/**
 * Since TagMap.Entry is thread safe and has involves complicated multi-thread type resolution code,
 * this test uses a different approach to stress ordering different combinations.
 *
 * <p>Each test produces a series of check-s encapsulated in a Check object.
 *
 * <p>Those checks are then shuffled to simulate different operation orderings - both in single
 * threaded and multi-threaded scenarios.
 *
 * @author dougqh
 */
public class TagMapEntryTest {
  @Test
  public void objectEntry() {
    test(
        () -> TagMap.Entry.newObjectEntry("foo", "bar"),
        TagMap.Entry.OBJECT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue("bar", entry),
                checkEquals("bar", entry::stringValue),
                checkTrue(entry::isObject)));
  }

  @Test
  public void anyEntry_object() {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", "bar"),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue("bar", entry),
                checkTrue(entry::isObject),
                checkKey("foo", entry),
                checkValue("bar", entry)));
  }

  @Test
  public void booleanEntry() {
    test(
        () -> TagMap.Entry.newBooleanEntry("foo", true),
        TagMap.Entry.BOOLEAN,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(true, entry),
                checkFalse(entry::isNumericPrimitive),
                checkType(TagMap.Entry.BOOLEAN, entry)));
  }

  @Test
  public void booleanEntry_boxed() {
    test(
        () -> TagMap.Entry.newBooleanEntry("foo", Boolean.valueOf(true)),
        TagMap.Entry.BOOLEAN,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(true, entry),
                checkFalse(entry::isNumericPrimitive),
                checkType(TagMap.Entry.BOOLEAN, entry)));
  }

  @Test
  public void anyEntry_boolean() {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Boolean.valueOf(true)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(true, entry),
                checkFalse(entry::isNumericPrimitive),
                checkType(TagMap.Entry.BOOLEAN, entry),
                checkValue(true, entry)));
  }

  @Test
  public void intEntry() {
    test(
        () -> TagMap.Entry.newIntEntry("foo", 20),
        TagMap.Entry.INT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(20, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.INT, entry)));
  }

  @Test
  public void intEntry_boxed() {
    test(
        () -> TagMap.Entry.newIntEntry("foo", Integer.valueOf(20)),
        TagMap.Entry.INT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(20, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.INT, entry)));
  }

  @Test
  public void anyEntry_int() {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Integer.valueOf(20)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(20, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.INT, entry),
                checkValue(20, entry)));
  }

  @Test
  public void longEntry() {
    test(
        () -> TagMap.Entry.newLongEntry("foo", 1_048_576L),
        TagMap.Entry.LONG,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(1_048_576L, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.LONG, entry)));
  }

  @Test
  public void longEntry_boxed() {
    test(
        () -> TagMap.Entry.newLongEntry("foo", Long.valueOf(1_048_576L)),
        TagMap.Entry.LONG,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(1_048_576L, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.LONG, entry)));
  }

  @Test
  public void anyEntry_long() {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Long.valueOf(1_048_576L)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(1_048_576L, entry),
                checkTrue(entry::isNumericPrimitive),
                checkTrue(() -> entry.is(TagMap.Entry.LONG)),
                checkValue(1_048_576L, entry)));
  }

  @Test
  public void doubleEntry() {
    test(
        () -> TagMap.Entry.newDoubleEntry("foo", Math.PI),
        TagMap.Entry.DOUBLE,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(Math.PI, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.DOUBLE, entry)));
  }

  @Test
  public void doubleEntry_boxed() {
    test(
        () -> TagMap.Entry.newDoubleEntry("foo", Double.valueOf(Math.PI)),
        TagMap.Entry.DOUBLE,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(Math.PI, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.DOUBLE, entry)));
  }

  @Test
  public void anyEntry_double() {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Double.valueOf(Math.PI)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(Math.PI, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.DOUBLE, entry),
                checkValue(Math.PI, entry)));
  }

  @Test
  public void floatEntry() {
    test(
        () -> TagMap.Entry.newFloatEntry("foo", 2.718281828f),
        TagMap.Entry.FLOAT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(2.718281828f, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.FLOAT, entry)));
  }

  @Test
  public void floatEntry_boxed() {
    test(
        () -> TagMap.Entry.newFloatEntry("foo", Float.valueOf(2.718281828f)),
        TagMap.Entry.FLOAT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(2.718281828f, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.FLOAT, entry)));
  }

  @Test
  public void anyEntry_float() {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Float.valueOf(2.718281828f)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(2.718281828f, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.FLOAT, entry)));
  }

  static final void test(
      Supplier<TagMap.Entry> entrySupplier, byte rawType, Function<Entry, Check> checks) {
    // repeat the test several times to exercise different orderings
    for (int i = 0; i < 10; ++i) {
      Entry entry = entrySupplier.get();
      assertEquals(rawType, entry.rawType);

      assertChecks(checks.apply(entry));
    }
  }

  static final void assertChecks(Check check) {
    check.check();
  }

  static final Check checkKey(String expected, TagMap.Entry entry) {
    return multiCheck(checkEquals(expected, entry::tag), checkEquals(expected, entry::getKey));
  }

  static final Check checkValue(Object expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::objectValue),
        checkEquals(expected, entry::getValue),
        checkEquals(expected.toString(), entry::stringValue));
  }

  static final Check checkValue(boolean expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::booleanValue),
        checkEquals(Boolean.valueOf(expected), entry::objectValue),
        checkEquals(expected ? 1 : 0, entry::intValue),
        checkEquals(expected ? 1L : 0L, entry::longValue),
        checkEquals(expected ? 1D : 0D, entry::doubleValue),
        checkEquals(expected ? 1F : 0F, entry::floatValue),
        checkEquals(Boolean.toString(expected), entry::stringValue));
  }

  static final Check checkValue(int expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::intValue),
        checkEquals((long) expected, entry::longValue),
        checkEquals((float) expected, entry::floatValue),
        checkEquals((double) expected, entry::doubleValue),
        checkEquals(Integer.valueOf(expected), entry::objectValue),
        checkEquals(expected != 0, entry::booleanValue),
        checkEquals(Integer.toString(expected), entry::stringValue));
  }

  static final Check checkValue(long expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::longValue),
        checkEquals((int) expected, entry::intValue),
        checkEquals((float) expected, entry::floatValue),
        checkEquals((double) expected, entry::doubleValue),
        checkEquals(Long.valueOf(expected), entry::objectValue),
        checkEquals(expected != 0L, entry::booleanValue),
        checkEquals(Long.toString(expected), entry::stringValue));
  }

  static final Check checkValue(double expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::doubleValue),
        checkEquals((int) expected, entry::intValue),
        checkEquals((long) expected, entry::longValue),
        checkEquals((float) expected, entry::floatValue),
        checkEquals(Double.valueOf(expected), entry::objectValue),
        checkEquals(expected != 0D, entry::booleanValue),
        checkEquals(Double.toString(expected), entry::stringValue));
  }

  static final Check checkValue(float expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::floatValue),
        checkEquals((int) expected, entry::intValue),
        checkEquals((long) expected, entry::longValue),
        checkEquals((double) expected, entry::doubleValue),
        checkEquals(expected != 0F, entry::booleanValue),
        checkEquals(Float.valueOf(expected), entry::objectValue),
        checkEquals(Float.toString(expected), entry::stringValue));
  }

  static final Check checkType(byte entryType, TagMap.Entry entry) {
    return () -> assertTrue(entry.is(entryType), "type is " + entryType);
  }

  static final Check multiCheck(Check... checks) {
    return new MultipartCheck(checks);
  }

  static final Check checkFalse(Supplier<Boolean> actual) {
    return () -> assertFalse(actual.get(), actual.toString());
  }

  static final Check checkTrue(Supplier<Boolean> actual) {
    return () -> assertTrue(actual.get(), actual.toString());
  }

  static final Check checkEquals(float expected, Supplier<Float> actual) {
    return () -> assertEquals(expected, actual.get(), actual.toString());
  }

  static final Check checkEquals(int expected, Supplier<Integer> actual) {
    return () -> assertEquals(expected, actual.get(), actual.toString());
  }

  static final Check checkEquals(double expected, Supplier<Double> actual) {
    return () -> assertEquals(expected, actual.get(), actual.toString());
  }

  static final Check checkEquals(long expected, Supplier<Long> actual) {
    return () -> assertEquals(expected, actual.get(), actual.toString());
  }

  static final Check checkEquals(boolean expected, Supplier<Boolean> actual) {
    return () -> assertEquals(expected, actual.get(), actual.toString());
  }

  static final Check checkEquals(Object expected, Supplier<Object> actual) {
    return () -> assertEquals(expected, actual.get(), actual.toString());
  }

  @FunctionalInterface
  interface Check {
    void check();

    default void flatten(List<Check> checkAccumulator) {
      checkAccumulator.add(this);
    }
  }

  static final class MultipartCheck implements Check {
    private final Check[] checks;

    MultipartCheck(Check... checks) {
      this.checks = checks;
    }

    @Override
    public void check() {
      List<Check> checkAccumulator = new ArrayList<>();
      for (Check check : this.checks) {
        check.flatten(checkAccumulator);
      }

      Collections.shuffle(checkAccumulator);
      for (Check check : checkAccumulator) {
        check.check();
      }
    }

    @Override
    public void flatten(List<Check> checkAccumulator) {
      for (Check check : this.checks) {
        check.flatten(checkAccumulator);
      }
    }
  }
}
