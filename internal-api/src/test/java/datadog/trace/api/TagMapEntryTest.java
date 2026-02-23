package datadog.trace.api;

import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMap.Entry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
  public void isNumericPrimitive() {
    assertFalse(TagMap.Entry._isNumericPrimitive(TagMap.Entry.ANY));
    assertFalse(TagMap.Entry._isNumericPrimitive(TagMap.Entry.BOOLEAN));
    assertFalse(TagMap.Entry._isNumericPrimitive(TagMap.Entry.CHAR_RESERVED));
    assertFalse(TagMap.Entry._isNumericPrimitive(TagMap.Entry.OBJECT));

    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.BYTE_RESERVED));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.SHORT_RESERVED));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.INT));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.LONG));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.FLOAT));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.DOUBLE));
  }

  @Test
  @DisplayName("TagMap.Entry.create: Object")
  public void createObject() {
    Map<String, String> map = new HashMap<>();

    test(
        () -> TagMap.Entry.create("foo", map),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(map, entry),
                checkTrue(entry::isObject),
                checkFalse(entry::isNumber),
                checkType(TagMap.Entry.OBJECT, entry)));
  }

  @Test
  @DisplayName("TagMap.Entry.create: null Object")
  public void createEntryNullObject() {
    assertNull(TagMap.Entry.create("foo", null));
  }

  @Test
  @DisplayName("TagMap.Entry.create: CharSequence")
  public void createCharSequence() {
    test(
        () -> TagMap.Entry.create("foo", "bar"),
        TagMap.Entry.OBJECT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue("bar", entry),
                checkEquals("bar", entry::stringValue),
                checkFalse(entry::isNumber),
                checkTrue(entry::isObject),
                checkType(TagMap.Entry.OBJECT, entry)));
  }

  @Test
  @DisplayName("TagMap.Entry.create: null CharSequence")
  public void createNullCharSequence() {
    assertNull(TagMap.Entry.create("foo", (String) null));
  }

  @Test
  @DisplayName("TagMap.Entry.create: empty CharSequence")
  public void createEmptyCharSequence() {
    assertNull(TagMap.Entry.create("foo", ""));
  }

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
                checkFalse(entry::isNumber),
                checkTrue(entry::isObject),
                checkType(TagMap.Entry.OBJECT, entry)));
  }

  @Test
  @DisplayName("anyEntry: Object")
  public void anyEntryObject() {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", "bar"),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue("bar", entry),
                checkTrue(entry::isObject),
                checkFalse(entry::isNumber),
                checkType(TagMap.EntryReader.OBJECT, entry)));
  }

  @ParameterizedTest
  @DisplayName("TagMap.Entry.create: boolean")
  @ValueSource(booleans = {false, true})
  public void createBoolean(boolean value) {
    test(
        () -> TagMap.Entry.create("foo", value),
        TagMap.Entry.BOOLEAN,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkFalse(entry::isNumericPrimitive),
                checkFalse(entry::isNumber),
                checkFalse(entry::isObject),
                checkType(TagMap.Entry.BOOLEAN, entry)));
  }

  @ParameterizedTest
  @DisplayName("newBooleanEntry: boolean")
  @ValueSource(booleans = {false, true})
  public void newBooleanEntry(boolean value) {
    test(
        () -> TagMap.Entry.create("foo", value),
        TagMap.Entry.BOOLEAN,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkFalse(entry::isNumericPrimitive),
                checkFalse(entry::isNumber),
                checkFalse(entry::isObject),
                checkType(TagMap.Entry.BOOLEAN, entry)));
  }

  @ParameterizedTest
  @DisplayName("newBooleanEntry: Boolean")
  @ValueSource(booleans = {false, true})
  public void newBooleanEntryBoxed(boolean value) {
    test(
        () -> TagMap.Entry.newBooleanEntry("foo", Boolean.valueOf(value)),
        TagMap.Entry.BOOLEAN,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkFalse(entry::isNumericPrimitive),
                checkFalse(entry::isNumber),
                checkFalse(entry::isObject),
                checkType(TagMap.Entry.BOOLEAN, entry)));
  }

  @ParameterizedTest
  @DisplayName("newAnyEntry: Boolean")
  @ValueSource(booleans = {false, true})
  public void newAnyEntryBoolean(boolean value) {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Boolean.valueOf(value)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkFalse(entry::isNumericPrimitive),
                checkFalse(entry::isNumber),
                checkFalse(entry::isObject),
                checkType(TagMap.Entry.BOOLEAN, entry),
                checkValue(value, entry)));
  }

  @ParameterizedTest
  @DisplayName("TagMap.Entry.create: int")
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void createInt(int value) {
    test(
        () -> TagMap.Entry.create("foo", value),
        TagMap.Entry.INT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkInstanceOf(Number.class, entry),
                checkType(TagMap.Entry.INT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newIntEntry: int")
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void newIntEntry(int value) {
    test(
        () -> TagMap.Entry.newIntEntry("foo", value),
        TagMap.Entry.INT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkInstanceOf(Number.class, entry),
                checkType(TagMap.Entry.INT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newIntEntry: Integer")
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void newIntEntryBoxed(int value) {
    test(
        () -> TagMap.Entry.newIntEntry("foo", Integer.valueOf(value)),
        TagMap.Entry.INT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkInstanceOf(Number.class, entry),
                checkType(TagMap.Entry.INT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newIntEntry: Short")
  @ValueSource(shorts = {Short.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Short.MAX_VALUE})
  public void intEntryBoxedShort(short value) {
    test(
        () -> TagMap.Entry.newIntEntry("foo", Short.valueOf(value)),
        TagMap.Entry.INT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkInstanceOf(Number.class, entry),
                checkType(TagMap.Entry.INT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newIntEntry: Byte")
  @ValueSource(bytes = {Byte.MIN_VALUE, -32, -1, 0, 1, 32, Byte.MAX_VALUE})
  public void intEntryBoxedByte(byte value) {
    test(
        () -> TagMap.Entry.newIntEntry("foo", Byte.valueOf(value)),
        TagMap.Entry.INT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkInstanceOf(Number.class, entry),
                checkType(TagMap.Entry.INT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newAnyEntry: Integer")
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void newAnyEntryInteger(int value) {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Integer.valueOf(value)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkInstanceOf(Number.class, entry),
                checkType(TagMap.Entry.INT, entry),
                checkValue(value, entry)));
  }

  @ParameterizedTest
  @DisplayName("TagMap.Entry.create: long")
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
  public void createLong(long value) {
    test(
        () -> TagMap.Entry.create("foo", value),
        TagMap.Entry.LONG,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.LONG, entry)));
  }

  @ParameterizedTest
  @DisplayName("newLongEntry: long")
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
  public void newLongEntry(long value) {
    test(
        () -> TagMap.Entry.newLongEntry("foo", value),
        TagMap.Entry.LONG,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.LONG, entry)));
  }

  @ParameterizedTest
  @DisplayName("newLongEntry: Long")
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
  public void newLongEntryBoxed(long value) {
    test(
        () -> TagMap.Entry.newLongEntry("foo", Long.valueOf(value)),
        TagMap.Entry.LONG,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkType(TagMap.Entry.LONG, entry)));
  }

  @ParameterizedTest
  @DisplayName("newAnyEntry: Long")
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
  public void newAnyEntryLong(long value) {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Long.valueOf(value)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkTrue(() -> entry.is(TagMap.Entry.LONG)),
                checkValue(value, entry)));
  }

  @ParameterizedTest
  @DisplayName("TagMap.Entry.create: float")
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void createFloat(float value) {
    test(
        () -> TagMap.Entry.create("foo", value),
        TagMap.Entry.FLOAT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.FLOAT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newFloatEntry: float")
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void newFloatEntry(float value) {
    test(
        () -> TagMap.Entry.newFloatEntry("foo", value),
        TagMap.Entry.FLOAT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.FLOAT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newFloatEntry: Float")
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void newFloatEntryBoxed(float value) {
    test(
        () -> TagMap.Entry.newFloatEntry("foo", Float.valueOf(value)),
        TagMap.Entry.FLOAT,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkType(TagMap.Entry.FLOAT, entry)));
  }

  @ParameterizedTest
  @DisplayName("newAnyEntry: Float")
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void newAnyEntryFloat(float value) {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Float.valueOf(value)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.FLOAT, entry)));
  }

  @ParameterizedTest
  @DisplayName("TagMap.Entry.create: double")
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void createDouble(double value) {
    test(
        () -> TagMap.Entry.create("foo", value),
        TagMap.Entry.DOUBLE,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkType(TagMap.Entry.DOUBLE, entry)));
  }

  @ParameterizedTest
  @DisplayName("newDoubleEntry: double")
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void doubleEntry_via_create(double value) {
    test(
        () -> TagMap.Entry.create("foo", value),
        TagMap.Entry.DOUBLE,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkType(TagMap.Entry.DOUBLE, entry)));
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void doubleEntry(double value) {
    test(
        () -> TagMap.Entry.newDoubleEntry("foo", value),
        TagMap.Entry.DOUBLE,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkIsNumericPrimitive(entry),
                checkType(TagMap.Entry.DOUBLE, entry)));
  }

  @ParameterizedTest
  @DisplayName("newDoubleEntry: Double")
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void newDoubleEntryBoxed(double value) {
    test(
        () -> TagMap.Entry.newDoubleEntry("foo", Double.valueOf(value)),
        TagMap.Entry.DOUBLE,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.DOUBLE, entry)));
  }

  @ParameterizedTest
  @DisplayName("newAnyEntry: Double")
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void anyEntry_double(double value) {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Double.valueOf(value)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkTrue(entry::isNumericPrimitive),
                checkType(TagMap.Entry.DOUBLE, entry),
                checkValue(value, entry)));
  }

  @Test
  public void removalChange() {
    TagMap.EntryChange removalChange = TagMap.EntryChange.newRemoval("foo");
    assertTrue(removalChange.isRemoval());
  }

  static final int NUM_THREADS = 4;
  static final ExecutorService EXECUTOR =
      Executors.newFixedThreadPool(
          NUM_THREADS,
          new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
              Thread thread = new Thread(r, "multithreaded-test-runner");
              thread.setDaemon(true);
              return thread;
            }
          });

  static final void test(
      Supplier<TagMap.Entry> entrySupplier, byte rawType, Function<Entry, Check> checkSupplier) {

    Function<Entry, Check> combinedCheckSupplier =
        (entry) -> {
          return multiCheck(
              checkSupplier.apply(entry),
              checkSame(entry, entry.entry()),
              checkSame(entry, entry.mapEntry()));
        };

    // repeat the test several times to exercise different orderings in this thread
    for (int i = 0; i < 10; ++i) {
      testSingleThreaded(entrySupplier, rawType, combinedCheckSupplier);
    }

    // same for multi-threaded
    for (int i = 0; i < 5; ++i) {
      testMultiThreaded(entrySupplier, rawType, combinedCheckSupplier);
    }
  }

  static final void testSingleThreaded(
      Supplier<TagMap.Entry> entrySupplier, byte rawType, Function<Entry, Check> checkSupplier) {
    Entry entry = entrySupplier.get();
    assertEquals(rawType, entry.rawType, "rawType");

    Check checks = checkSupplier.apply(entry);
    checks.check();
  }

  static final void testMultiThreaded(
      Supplier<TagMap.Entry> entrySupplier, byte rawType, Function<Entry, Check> checkSupplier) {
    Entry sharedEntry = entrySupplier.get();
    assertEquals(rawType, sharedEntry.rawType, "rawType");

    Check checks = checkSupplier.apply(sharedEntry);

    List<Callable<Void>> callables = new ArrayList<>(NUM_THREADS);
    for (int i = 0; i < NUM_THREADS; ++i) {
      // Different shuffle for each thread
      Check shuffledChecks = checks.shuffle();

      callables.add(
          () -> {
            shuffledChecks.check();

            return null;
          });
    }

    List<Future<Void>> futures;
    try {
      futures = EXECUTOR.invokeAll(callables);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();

      throw new IllegalStateException(e);
    }

    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Error) {
          throw (Error) cause;
        } else if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        } else {
          throw new IllegalStateException(cause);
        }
      }
    }
  }

  static final void assertChecks(Check check) {
    check.check();
  }

  static final Check checkKey(String expected, TagMap.Entry entry) {
    return multiCheck(checkEquals(expected, entry::tag), checkEquals(expected, entry::getKey));
  }

  static final <T> Supplier<T> of(Supplier<T> supplier, String identifier) {
    return new Supplier<T>() {
      @Override
      public T get() {
        return supplier.get();
      }

      @Override
      public String toString() {
        return identifier;
      }
    };
  }

  static final <I, O> Function<I, O> of(Function<I, O> func, String identifier) {
    return new Function<I, O>() {
      @Override
      public O apply(I input) {
        return func.apply(input);
      }

      @Override
      public String toString() {
        return identifier;
      }
    };
  }

  static final <I, O> Supplier<O> of(Function<I, O> output, Supplier<I> input) {
    return of(output, input, output.toString() + "(" + input.toString() + ")");
  }

  static final <I, O> Supplier<O> of(Function<I, O> output, Supplier<I> input, String identifier) {
    return of(() -> output.apply(input.get()), identifier);
  }

  static final Check checkIsNumericPrimitive(TagMap.Entry entry) {
    return multiCheck(
        checkTrue(entry::isNumericPrimitive, "Entry::isNumericPrimitive"),
        checkTrue(entry::isNumber, "Entry::isNumber"),
        checkInstanceOf(Number.class, entry),
        checkFalse(entry::isObject, "Entry::isObject"),
        checkTrue(of(TagValueConversions::isNumber, entry::objectValue), "isNumber(Object)"),
        checkTrue(
            of(TagValueConversions::isNumericPrimitive, entry::objectValue),
            "isNumericPrimitive(Object)"),
        checkFalse(of(TagValueConversions::isObject, entry::objectValue), "isObject(Object)"));
  }

  static final Check checkIsBigNumber(TagMap.Entry entry) {
    return multiCheck(
        checkFalse(entry::isNumericPrimitive, "Entry::isNumericPrimitive"),
        checkFalse(
            of(TagValueConversions::isNumericPrimitive, entry::objectValue),
            "isNumericPrimitive(Object)"),
        checkTrue(entry::isNumber, "Entry::isNumber"),
        checkTrue(entry::isObject, "Entry::isObject"),
        checkTrue(of(TagValueConversions::isNumber, entry::objectValue), "isNumber(Object)"),
        checkInstanceOf(Number.class, entry));
  }

  static final Check checkValue(Object expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::objectValue),
        checkEquals(expected, entry::getValue),
        checkEquals(expected.toString(), entry::stringValue));
  }

  static final Check checkValue(boolean expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::booleanValue, "Entry::booleanValue"),
        checkEquals(Boolean.valueOf(expected), entry::objectValue, "Entry::objectValue"),
        checkEquals(expected ? 1 : 0, entry::intValue, "Entry::intValue"),
        checkEquals(
            expected ? 1 : 0, of(TagValueConversions::toInt, entry::objectValue), "toInt(Object)"),
        checkEquals(expected ? 1L : 0L, entry::longValue, "Entry::longValue"),
        checkEquals(
            expected ? 1L : 0L,
            of(TagValueConversions::toLong, entry::objectValue),
            "toLong(Object)"),
        checkEquals(expected ? 1D : 0D, entry::doubleValue, "Entry::doubleValue"),
        checkEquals(
            expected ? 1D : 0D,
            of(TagValueConversions::toDouble, entry::objectValue),
            "toDouble(Object)"),
        checkEquals(expected ? 1F : 0F, entry::floatValue, "Entry::floatValue"),
        checkEquals(
            expected ? 1F : 0F,
            of(TagValueConversions::toFloat, entry::objectValue),
            "toFloat(Object)"),
        checkEquals(Boolean.toString(expected), entry::stringValue, "Entry::stringValue"),
        checkEquals(
            Boolean.toString(expected),
            of(TagValueConversions::toString, entry::objectValue),
            "toString(Object)"));
  }

  static final Check checkValue(int expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::intValue, "Entry::intValue"),
        checkEquals(Integer.valueOf(expected), entry::objectValue, "Entry::objectValue"),
        checkEquals((long) expected, entry::longValue, "Entry::longValue"),
        checkEquals(
            (long) expected, of(TagValueConversions::toLong, entry::objectValue), "toLong(Object)"),
        checkEquals((float) expected, entry::floatValue, "Entry::floatValue"),
        checkEquals(
            (float) expected,
            of(TagValueConversions::toFloat, entry::objectValue),
            "toFloat(Object)"),
        checkEquals((double) expected, entry::doubleValue, "Entry::doubleValue"),
        checkEquals(
            (double) expected,
            of(TagValueConversions::toDouble, entry::objectValue),
            "toDouble(Object)"),
        checkEquals(expected != 0, entry::booleanValue, "Entry::booleanValue"),
        checkEquals(
            expected != 0,
            of(TagValueConversions::toBoolean, entry::objectValue),
            "toBoolean(Object)"),
        checkEquals(Integer.toString(expected), entry::stringValue, "Entry::stringValue"),
        checkEquals(
            Integer.toString(expected),
            of(TagValueConversions::toString, entry::objectValue),
            "toString(Object)"));
  }

  static final Check checkValue(long expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::longValue, "Entry::longValue"),
        checkEquals(Long.valueOf(expected), entry::objectValue, "Entry::objectValue"),
        checkEquals((int) expected, entry::intValue, "Entry::intValue"),
        checkEquals(
            (int) expected, of(TagValueConversions::toInt, entry::objectValue), "toInt(Object)"),
        checkEquals((float) expected, entry::floatValue, "Entry::floatValue"),
        checkEquals(
            (float) expected,
            of(TagValueConversions::toFloat, entry::objectValue),
            "toFloat(Object)"),
        checkEquals((double) expected, entry::doubleValue, "Entry::doubleValue"),
        checkEquals(
            (double) expected,
            of(TagValueConversions::toDouble, entry::objectValue),
            "toDouble(Object)"),
        checkEquals(expected != 0L, entry::booleanValue, "Entry::booleanValue"),
        checkEquals(
            expected != 0L,
            of(TagValueConversions::toBoolean, entry::objectValue),
            "toBoolean(Object)"),
        checkEquals(Long.toString(expected), entry::stringValue, "Entry::stringValue"),
        checkEquals(
            Long.toString(expected),
            of(TagValueConversions::toString, entry::objectValue),
            "toString(Object)"));
  }

  static final Check checkValue(double expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::doubleValue, "Entry::doubleValue"),
        checkEquals(Double.valueOf(expected), entry::objectValue, "Entry::objectValue"),
        checkEquals((int) expected, entry::intValue, "Entry::intValue"),
        checkEquals(
            (int) expected, of(TagValueConversions::toInt, entry::objectValue), "toInt(Object)"),
        checkEquals((long) expected, entry::longValue, "Entry::longValue"),
        checkEquals(
            (long) expected, of(TagValueConversions::toLong, entry::objectValue), "toLong(Object)"),
        checkEquals((float) expected, entry::floatValue, "Entry::floatValue"),
        checkEquals(
            (float) expected,
            of(TagValueConversions::toFloat, entry::objectValue),
            "toFloat(Object)"),
        checkEquals(expected != 0D, entry::booleanValue, "Entry::booleanValue"),
        checkEquals(
            expected != 0D,
            of(TagValueConversions::toBoolean, entry::objectValue),
            "toBoolean(Object)"),
        checkEquals(Double.toString(expected), entry::stringValue, "Entry::stringValue"),
        checkEquals(
            Double.toString(expected),
            of(TagValueConversions::toString, entry::objectValue),
            "toString(Object)"));
  }

  static final Check checkValue(float expected, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(expected, entry::floatValue, "Entry::floatValue"),
        checkEquals(Float.valueOf(expected), entry::objectValue, "Entry::objectValue"),
        checkEquals((int) expected, entry::intValue, "Entry::intValue"),
        checkEquals(
            (int) expected, of(TagValueConversions::toInt, entry::objectValue), "toInt(Object)"),
        checkEquals((long) expected, entry::longValue, "Entry::longValue"),
        checkEquals(
            (long) expected, of(TagValueConversions::toLong, entry::objectValue), "toLong(Object)"),
        checkEquals((double) expected, entry::doubleValue, "Entry::doubleValue"),
        checkEquals(
            (double) expected,
            of(TagValueConversions::toDouble, entry::objectValue),
            "toDouble(Object)"),
        checkEquals(expected != 0F, entry::booleanValue, "Entry::booleanValue"),
        checkEquals(
            expected != 0F,
            of(TagValueConversions::toBoolean, entry::objectValue),
            "toBoolean(Object)"),
        checkEquals(Float.toString(expected), entry::stringValue, "Entry::stringValue"),
        checkEquals(
            Float.toString(expected),
            of(TagValueConversions::toString, entry::objectValue),
            "toString(Object)"));
  }

  public static Check checkNumber(Number number, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(number, entry::objectValue),
        checkEquals(number.intValue(), entry::intValue),
        checkEquals(number.intValue(), of(TagValueConversions::toInt, entry::objectValue)),
        checkEquals(number.longValue(), entry::longValue),
        checkEquals(number.longValue(), of(TagValueConversions::toLong, entry::objectValue)),
        checkEquals(number.floatValue(), entry::floatValue),
        checkEquals(number.floatValue(), of(TagValueConversions::toFloat, entry::objectValue)),
        checkEquals(number.doubleValue(), entry::doubleValue),
        checkEquals(number.doubleValue(), of(TagValueConversions::toDouble, entry::objectValue)),
        checkEquals(number.toString(), entry::stringValue),
        checkEquals(number.toString(), of(TagValueConversions::toString, entry::objectValue)));
  }

  static final Check checkInstanceOf(Class<?> klass, TagMap.Entry entry) {
    return checkTrue(
        () -> klass.isAssignableFrom(entry.objectValue().getClass()),
        "instanceof " + klass.getSimpleName());
  }

  static final Check checkType(byte entryType, TagMap.Entry entry) {
    // TODO: TVC checks
    return multiCheck(
        checkTrue(() -> entry.is(entryType), "Entry::is(type) type=" + entryType),
        checkEquals(entryType, entry::type, "Entry::type check"));
  }

  static final Check multiCheck(Check... checks) {
    return new MultipartCheck(checks);
  }

  static final Check checkSame(Object expected, Object actual) {
    return () -> assertSame(expected, actual);
  }

  static final Check checkFalse(Supplier<Boolean> actual) {
    return checkFalse(actual, actual.toString());
  }

  static final Check checkFalse(Supplier<Boolean> actual, String identifier) {
    return () -> assertFalse(actual.get(), identifier);
  }

  static final Check checkTrue(Supplier<Boolean> actual) {
    return checkTrue(actual, actual.toString());
  }

  static final Check checkTrue(Supplier<Boolean> actual, String identifier) {
    return () -> assertTrue(actual.get(), identifier);
  }

  static final Check checkEquals(float expected, Supplier<Float> actual) {
    return checkEquals(expected, actual, actual.toString());
  }

  static final Check checkEquals(float expected, Supplier<Float> actual, String identifier) {
    return () -> assertEquals(expected, actual.get().floatValue(), identifier);
  }

  static final Check checkEquals(int expected, Supplier<Integer> actual) {
    return checkEquals(expected, actual, actual.toString());
  }

  static final Check checkEquals(int expected, Supplier<Integer> actual, String identifier) {
    return () -> assertEquals(expected, actual.get().intValue(), identifier);
  }

  static final Check checkEquals(double expected, Supplier<Double> actual) {
    return checkEquals(expected, actual, actual.toString());
  }

  static final Check checkEquals(double expected, Supplier<Double> actual, String identifier) {
    return () -> assertEquals(expected, actual.get().doubleValue(), identifier);
  }

  static final Check checkEquals(long expected, Supplier<Long> actual) {
    return checkEquals(expected, actual, actual.toString());
  }

  static final Check checkEquals(long expected, Supplier<Long> actual, String identifier) {
    return () -> assertEquals(expected, actual.get().longValue(), identifier);
  }

  static final Check checkEquals(boolean expected, Supplier<Boolean> actual) {
    return checkEquals(expected, actual, actual.toString());
  }

  static final Check checkEquals(boolean expected, Supplier<Boolean> actual, String identifier) {
    return () -> assertEquals(expected, actual.get().booleanValue(), identifier);
  }

  static final <T> Check checkEquals(T expected, Supplier<? extends T> actual) {
    return checkEquals(expected, actual, actual.toString());
  }

  static final <T> Check checkEquals(T expected, Supplier<? extends T> actual, String identifier) {
    return () -> assertEquals(expected, actual.get(), identifier);
  }

  @FunctionalInterface
  interface Check {
    void check();

    default Check shuffle() {
      return this;
    }

    default void flatten(List<Check> checkAccumulator) {
      checkAccumulator.add(this);
    }
  }

  static final class MultipartCheck implements Check {
    private final Check[] checks;

    MultipartCheck(Check... checks) {
      this.checks = checks;
    }

    private List<Check> shuffleChecks() {
      List<Check> checkAccumulator = new ArrayList<>();
      for (Check check : this.checks) {
        check.flatten(checkAccumulator);
      }

      Collections.shuffle(checkAccumulator);
      return checkAccumulator;
    }

    @Override
    public void check() {
      for (Check check : this.shuffleChecks()) {
        check.check();
      }
    }

    @Override
    public Check shuffle() {
      List<Check> shuffled = this.shuffleChecks();

      return new Check() {
        @Override
        public void check() {
          for (Check check : shuffled) {
            check.check();
          }
        }
      };
    }

    @Override
    public void flatten(List<Check> checkAccumulator) {
      for (Check check : this.checks) {
        check.flatten(checkAccumulator);
      }
    }
  }
}
