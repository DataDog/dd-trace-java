package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMap.Entry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;
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
    assertFalse(TagMap.Entry._isNumericPrimitive(TagMap.Entry.CHAR));
    assertFalse(TagMap.Entry._isNumericPrimitive(TagMap.Entry.OBJECT));

    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.BYTE));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.SHORT));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.INT));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.LONG));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.FLOAT));
    assertTrue(TagMap.Entry._isNumericPrimitive(TagMap.Entry.DOUBLE));
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
                checkTrue(entry::isObject),
                checkFalse(entry::isNumber)));
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
                checkFalse(entry::isNumber),
                checkKey("foo", entry),
                checkValue("bar", entry)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void booleanEntry(boolean value) {
    test(
        () -> TagMap.Entry.newBooleanEntry("foo", value),
        TagMap.Entry.BOOLEAN,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkFalse(entry::isNumericPrimitive),
                checkFalse(entry::isNumber),
                checkType(TagMap.Entry.BOOLEAN, entry)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void booleanEntry_boxed(boolean value) {
    test(
        () -> TagMap.Entry.newBooleanEntry("foo", Boolean.valueOf(value)),
        TagMap.Entry.BOOLEAN,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkFalse(entry::isNumericPrimitive),
                checkFalse(entry::isNumber),
                checkType(TagMap.Entry.BOOLEAN, entry)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void anyEntry_boolean(boolean value) {
    test(
        () -> TagMap.Entry.newAnyEntry("foo", Boolean.valueOf(value)),
        TagMap.Entry.ANY,
        (entry) ->
            multiCheck(
                checkKey("foo", entry),
                checkValue(value, entry),
                checkFalse(entry::isNumericPrimitive),
                checkFalse(entry::isNumber),
                checkType(TagMap.Entry.BOOLEAN, entry),
                checkValue(value, entry)));
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void intEntry(int value) {
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
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void intEntry_boxed(int value) {
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
  @ValueSource(ints = {Integer.MIN_VALUE, -256, -128, -1, 0, 1, 128, 256, Integer.MAX_VALUE})
  public void anyEntry_int(int value) {
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
  public void longEntry(long value) {
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
  public void longEntry_boxed(long value) {
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
  public void anyEntry_long(long value) {
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
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void floatEntry(float value) {
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
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void floatEntry_boxed(float value) {
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
  @ValueSource(floats = {Float.MIN_VALUE, -1F, 0F, 1F, 2.171828F, 3.1415F, Float.MAX_VALUE})
  public void anyEntry_float(float value) {
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
  @ValueSource(
      doubles = {Double.MIN_VALUE, Float.MIN_VALUE, -1D, 0D, 1D, Math.E, Math.PI, Double.MAX_VALUE})
  public void doubleEntry_boxed(double value) {
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
      Supplier<TagMap.Entry> entrySupplier, byte rawType, Function<Entry, Check> checks) {
    // repeat the test several times to exercise different orderings in this thread
    for (int i = 0; i < 10; ++i) {
      testSingleThreaded(entrySupplier, rawType, checks);
    }

    // same for multi-threaded
    for (int i = 0; i < 5; ++i) {
      testMultiThreaded(entrySupplier, rawType, checks);
    }
  }

  static final void testSingleThreaded(
      Supplier<TagMap.Entry> entrySupplier, byte rawType, Function<Entry, Check> checkSupplier) {
    Entry entry = entrySupplier.get();
    assertEquals(rawType, entry.rawType);

    Check checks = checkSupplier.apply(entry);
    checks.check();
  }

  static final void testMultiThreaded(
      Supplier<TagMap.Entry> entrySupplier, byte rawType, Function<Entry, Check> checkSupplier) {
    Entry sharedEntry = entrySupplier.get();
    assertEquals(rawType, sharedEntry.rawType);

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

  static final Check checkIsNumericPrimitive(TagMap.Entry entry) {
    return multiCheck(
        checkTrue(entry::isNumericPrimitive),
        checkTrue(entry::isNumber),
        checkInstanceOf(Number.class, entry));
  }

  static final Check checkIsBigNumber(TagMap.Entry entry) {
    return multiCheck(
        checkFalse(entry::isNumericPrimitive),
        checkTrue(entry::isNumber),
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

  public static Check checkNumber(Number number, TagMap.Entry entry) {
    return multiCheck(
        checkEquals(number, entry::objectValue),
        checkEquals(number.intValue(), entry::intValue),
        checkEquals(number.longValue(), entry::longValue),
        checkEquals(number.floatValue(), entry::floatValue),
        checkEquals(number.doubleValue(), entry::doubleValue),
        checkEquals(number.toString(), entry::stringValue));
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

  static final Check checkInstanceOf(Class<?> klass, TagMap.Entry entry) {
    return () ->
        assertTrue(
            klass.isAssignableFrom(entry.objectValue().getClass()),
            "instanceof " + klass.getSimpleName());
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
    return () -> assertEquals(expected, actual.get().floatValue(), actual.toString());
  }

  static final Check checkEquals(int expected, Supplier<Integer> actual) {
    return () -> assertEquals(expected, actual.get().intValue(), actual.toString());
  }

  static final Check checkEquals(double expected, Supplier<Double> actual) {
    return () -> assertEquals(expected, actual.get().doubleValue(), actual.toString());
  }

  static final Check checkEquals(long expected, Supplier<Long> actual) {
    return () -> assertEquals(expected, actual.get().longValue(), actual.toString());
  }

  static final Check checkEquals(boolean expected, Supplier<Boolean> actual) {
    return () -> assertEquals(expected, actual.get().booleanValue(), actual.toString());
  }

  static final Check checkEquals(Object expected, Supplier<Object> actual) {
    return () -> assertEquals(expected, actual.get(), actual.toString());
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
