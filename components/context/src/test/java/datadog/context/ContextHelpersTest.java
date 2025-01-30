package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.context.ContextHelpers.CURRENT;
import static datadog.context.ContextHelpers.combine;
import static datadog.context.ContextHelpers.findAll;
import static datadog.context.ContextHelpers.findFirst;
import static datadog.context.ContextTest.BOOLEAN_KEY;
import static datadog.context.ContextTest.FLOAT_KEY;
import static datadog.context.ContextTest.LONG_KEY;
import static datadog.context.ContextTest.STRING_KEY;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.logging.Level.ALL;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.function.BinaryOperator;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ContextHelpersTest {
  private static final Object CARRIER_1 = new Object();
  private static final Object CARRIER_2 = new Object();
  private static final Object UNSET_CARRIER = new Object();
  private static final Object NON_CARRIER = new Object();
  private static final String VALUE_1 = "value1";
  private static final String VALUE_2 = "value2";

  @BeforeAll
  static void init() {
    Context context1 = root().with(STRING_KEY, VALUE_1);
    context1.attachTo(CARRIER_1);

    Context context2 = root().with(STRING_KEY, VALUE_2);
    context2.attachTo(CARRIER_2);

    root().attachTo(UNSET_CARRIER);
  }

  @ParameterizedTest
  @MethodSource("findFirstArguments")
  void testFindFirst(Object[] carriers, String expected) {
    assertEquals(expected, findFirst(STRING_KEY, carriers), "Cannot find first value");
  }

  static Stream<Arguments> findFirstArguments() {
    return Stream.of(
        arguments(emptyArray(), null),
        arguments(arrayOf(NON_CARRIER), null),
        arguments(arrayOf(UNSET_CARRIER), null),
        arguments(arrayOf(CARRIER_1), VALUE_1),
        arguments(arrayOf(CARRIER_1, CARRIER_2), VALUE_1),
        arguments(arrayOf(NON_CARRIER, CARRIER_1), VALUE_1),
        arguments(arrayOf(UNSET_CARRIER, CARRIER_1), VALUE_1),
        arguments(arrayOf(CARRIER_1, NON_CARRIER), VALUE_1),
        arguments(arrayOf(CARRIER_1, UNSET_CARRIER), VALUE_1));
  }

  @ParameterizedTest
  @MethodSource("findAllArguments")
  void testFindAll(Object[] carriers, Iterable<String> expected) {
    assertIterableEquals(expected, findAll(STRING_KEY, carriers), "Cannot find all values");
  }

  static Stream<Arguments> findAllArguments() {
    return Stream.of(
        arguments(emptyArray(), emptyList()),
        arguments(arrayOf(CARRIER_1), singleton(VALUE_1)),
        arguments(arrayOf(CARRIER_1, CARRIER_2), asList(VALUE_1, VALUE_2)),
        arguments(arrayOf(NON_CARRIER, CARRIER_1), singleton(VALUE_1)),
        arguments(arrayOf(UNSET_CARRIER, CARRIER_1), singleton(VALUE_1)),
        arguments(arrayOf(CARRIER_1, NON_CARRIER), singleton(VALUE_1)),
        arguments(arrayOf(CARRIER_1, UNSET_CARRIER), singleton(VALUE_1)));
  }

  @Test
  void testNullCarriers() {
    assertThrows(
        NullPointerException.class, () -> findFirst(null, CARRIER_1), "Should fail on null key");
    assertThrows(
        NullPointerException.class,
        () -> findFirst(STRING_KEY, (Object) null),
        "Should fail on null context");
    assertThrows(
        NullPointerException.class,
        () -> findFirst(STRING_KEY, null, CARRIER_1),
        "Should fail on null context");
    assertThrows(
        NullPointerException.class, () -> findAll(null, CARRIER_1), "Should fail on null key");
    assertThrows(
        NullPointerException.class,
        () -> findAll(STRING_KEY, (Object) null),
        "Should fail on null context");
    assertThrows(
        NullPointerException.class,
        () -> findAll(STRING_KEY, null, CARRIER_1),
        "Should fail on null context");
  }

  @Test
  void testCurrent() {
    assertEquals(root(), current(), "Current context is already set");
    Context context = root().with(STRING_KEY, VALUE_1);
    try (ContextScope ignored = context.attach()) {
      assertEquals(
          VALUE_1, findFirst(STRING_KEY, CURRENT), "Failed to get value from current context");
      assertIterableEquals(
          singleton(VALUE_1),
          findAll(STRING_KEY, CURRENT),
          "Failed to get value from current context");
    }
    assertEquals(root(), current(), "Current context stayed attached");
  }

  @Test
  void testCombine() {
    // Test general case
    Context context1 = root().with(STRING_KEY, VALUE_1).with(BOOLEAN_KEY, true);
    Context context2 = root().with(STRING_KEY, VALUE_2).with(FLOAT_KEY, 3.14F);
    Context context3 = root();
    Context context4 = root().with(FLOAT_KEY, 567F);

    Context combined = combine(context1, context2, context3, context4);
    assertEquals(VALUE_1, combined.get(STRING_KEY), "First duplicate value should be kept");
    assertEquals(true, combined.get(BOOLEAN_KEY), "Values from first context should be kept");
    assertEquals(3.14F, combined.get(FLOAT_KEY), "Values from second context should be kept");

    // Test SingletonContext optimization
    context1 = root().with(STRING_KEY, VALUE_1);
    context2 = root().with(STRING_KEY, VALUE_2);
    combined = combine(context1, context2);
    assertEquals(VALUE_1, combined.get(STRING_KEY), "First duplicate value should be kept");

    // Test IndexedContext optimization where later context has more elements
    context1 = root().with(STRING_KEY, VALUE_1).with(FLOAT_KEY, 3.14F);
    context2 = root().with(STRING_KEY, VALUE_2).with(LONG_KEY, 567L);
    combined = combine(context1, context2);
    assertEquals(VALUE_1, combined.get(STRING_KEY), "First duplicate value should be kept");
    assertEquals(3.14F, combined.get(FLOAT_KEY), "Values from first context should be kept");
    assertEquals(567L, combined.get(LONG_KEY), "Values from first context should be kept");

    // Test IndexedContext optimization where context has same size but only later context has value
    context1 = root().with(LONG_KEY, 567L).with(STRING_KEY, VALUE_1);
    context2 = root().with(LONG_KEY, 789L).with(FLOAT_KEY, 3.14F);
    combined = combine(context1, context2);
    assertEquals(567L, combined.get(LONG_KEY), "First duplicate value should be kept");
    assertEquals(VALUE_1, combined.get(STRING_KEY), "Values from first context should be kept");
    assertEquals(3.14F, combined.get(FLOAT_KEY), "Values from first context should be kept");

    // Test IndexedContext optimization with same size context and no new values
    context1 = root().with(STRING_KEY, VALUE_1).with(LONG_KEY, 567L);
    context2 = root().with(STRING_KEY, VALUE_2).with(LONG_KEY, 789L);
    combined = combine(context1, context2);
    assertEquals(VALUE_1, combined.get(STRING_KEY), "First duplicate value should be kept");
    assertEquals(567L, combined.get(LONG_KEY), "First duplicate value should be kept");
  }

  @Test
  void testCombiner() {
    ContextKey<ErrorStats> errorKey = ContextKey.named("error");
    Context context1 = root().with(errorKey, ErrorStats.from(INFO, 12)).with(STRING_KEY, VALUE_1);
    Context context2 = root().with(errorKey, ErrorStats.from(SEVERE, 1)).with(FLOAT_KEY, 3.14F);
    Context context3 = root().with(errorKey, ErrorStats.from(WARNING, 6)).with(BOOLEAN_KEY, true);

    BinaryOperator<Context> errorStatsMerger =
        (left, right) -> {
          ErrorStats mergedStats = ErrorStats.merge(left.get(errorKey), right.get(errorKey));
          return left.with(errorKey, mergedStats);
        };
    Context combined = combine(errorStatsMerger, context1, context2, context3);
    ErrorStats combinedStats = combined.get(errorKey);
    assertNotNull(combinedStats, "Failed to combined error stats");
    assertEquals(19, combinedStats.errorCount, "Failed to combine error stats");
    assertEquals(SEVERE, combinedStats.maxLevel, "Failed to combine error stats");
    assertNull(combined.get(STRING_KEY), "Combiner should drop any other context values");
    assertNull(combined.get(FLOAT_KEY), "Combiner should drop any other context values");
    assertNull(combined.get(BOOLEAN_KEY), "Combiner should drop any other context values");
  }

  @Test
  void testNullCombine() {
    assertThrows(
        NullPointerException.class,
        () -> combine((BinaryOperator<Context>) null, root()),
        "Should fail on null combiner");
    assertThrows(
        NullPointerException.class,
        () -> combine((left, right) -> left, (Context) null),
        "Should fail on null context");
  }

  private static class ErrorStats {
    int errorCount;
    Level maxLevel;

    public ErrorStats() {
      this.errorCount = 0;
      this.maxLevel = ALL;
    }

    public static ErrorStats from(Level logLevel, int count) {
      ErrorStats stats = new ErrorStats();
      stats.errorCount = count;
      stats.maxLevel = logLevel;
      return stats;
    }

    public static ErrorStats merge(ErrorStats a, ErrorStats b) {
      if (a == null) {
        return b;
      }
      Level maxLevel = a.maxLevel.intValue() > b.maxLevel.intValue() ? a.maxLevel : b.maxLevel;
      return from(maxLevel, a.errorCount + b.errorCount);
    }
  }

  private static Object[] emptyArray() {
    return new Object[0];
  }

  private static Object[] arrayOf(Object... objects) {
    return objects;
  }
}
