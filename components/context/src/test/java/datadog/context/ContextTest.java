package datadog.context;

import static datadog.context.Context.root;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContextTest {
  static final ContextKey<String> STRING_KEY = ContextKey.named("string-key");
  static final ContextKey<Boolean> BOOLEAN_KEY = ContextKey.named("boolean-key");
  static final ContextKey<Float> FLOAT_KEY = ContextKey.named("float-key");
  static final ContextKey<Long> LONG_KEY = ContextKey.named("long-key");

  @Test
  void testRoot() {
    // Test root is always the same
    Context root = root();
    assertEquals(root, root(), "Root context should be consistent");
    // Test root is not mutated
    String stringValue = "value";
    root.with(STRING_KEY, stringValue);
    assertEquals(root, root(), "Root context should be immutable");
  }

  static Stream<Context> contextImplementations() {
    SingletonContext singletonContext = new SingletonContext(ContextKey.named("test").index, true);
    IndexedContext indexedContext = new IndexedContext(new Object[0]);
    return Stream.of(Context.root(), singletonContext, indexedContext);
  }

  @ParameterizedTest
  @MethodSource("contextImplementations")
  void testWith(Context context) {
    // Test accessing non-set value
    assertNull(context.get(STRING_KEY));
    // Test retrieving value
    String stringValue = "value";
    Context context1 = context.with(STRING_KEY, stringValue);
    assertEquals(stringValue, context1.get(STRING_KEY));
    // Test overriding value
    String stringValue2 = "value2";
    Context context2 = context1.with(STRING_KEY, stringValue2);
    assertEquals(stringValue2, context2.get(STRING_KEY));
    // Test clearing value
    Context context3 = context2.with(STRING_KEY, null);
    assertNull(context3.get(STRING_KEY));
    // Test null key handling
    assertThrows(
        NullPointerException.class, () -> context.with(null, "test"), "Context forbids null keys");
    // Test null value handling
    assertDoesNotThrow(
        () -> context.with(BOOLEAN_KEY, null), "Null value should not throw exception");
    // Test null implicitly keyed value handling
    assertDoesNotThrow(() -> context.with(null), "Null implicitly keyed value not throw exception");
  }

  @ParameterizedTest
  @MethodSource("contextImplementations")
  void testWithPair(Context context) {
    // Test retrieving value
    String stringValue = "value";
    Context context1 = context.with(BOOLEAN_KEY, false, STRING_KEY, stringValue);
    assertEquals(stringValue, context1.get(STRING_KEY));
    assertEquals(false, context1.get(BOOLEAN_KEY));
    // Test overriding value
    String stringValue2 = "value2";
    Context context2 = context1.with(STRING_KEY, stringValue2, BOOLEAN_KEY, true);
    assertEquals(stringValue2, context2.get(STRING_KEY));
    assertEquals(true, context2.get(BOOLEAN_KEY));
    // Test clearing value
    Context context3 = context2.with(BOOLEAN_KEY, null, STRING_KEY, null);
    assertNull(context3.get(STRING_KEY));
    assertNull(context3.get(BOOLEAN_KEY));
    // Test null key handling
    assertThrows(
        NullPointerException.class,
        () -> context.with(null, "test", STRING_KEY, "test"),
        "Context forbids null keys");
    assertThrows(
        NullPointerException.class,
        () -> context.with(STRING_KEY, "test", null, "test"),
        "Context forbids null keys");
    // Test null value handling
    assertDoesNotThrow(
        () -> context.with(BOOLEAN_KEY, null, STRING_KEY, "test"),
        "Null value should not throw exception");
    assertDoesNotThrow(
        () -> context.with(STRING_KEY, "test", BOOLEAN_KEY, null),
        "Null value should not throw exception");
  }

  @ParameterizedTest
  @MethodSource("contextImplementations")
  void testGet(Context original) {
    // Setup context
    String value = "value";
    Context context = original.with(STRING_KEY, value);
    // Test null key handling
    assertThrows(NullPointerException.class, () -> context.get(null), "Context forbids null keys");
    // Test unset key
    assertNull(context.get(BOOLEAN_KEY), "Missing key expected to return null");
    // Test set key
    assertEquals(value, context.get(STRING_KEY), "Value expected to be retrieved");
  }

  @SuppressWarnings({
    "EqualsWithItself",
    "SimplifiableAssertion",
    "ConstantValue",
    "EqualsBetweenInconvertibleTypes"
  })
  @Test
  void testEqualsAndHashCode() {
    // Setup contexts
    Context root = root();
    Context context1 = root.with(STRING_KEY, "value");
    Context context2 = root.with(STRING_KEY, "value    ");
    Context context3 = root.with(STRING_KEY, "value    ".trim());
    Context context4 = root.with(STRING_KEY, "value").with(BOOLEAN_KEY, true);
    // Test equals on self
    assertTrue(root.equals(root));
    assertTrue(context1.equals(context1));
    assertTrue(context4.equals(context4));
    // Test equals on null
    assertFalse(context1.equals(null));
    assertFalse(context4.equals(null));
    // Test equals on different object type
    assertFalse(context1.equals("value"));
    assertFalse(context4.equals("value"));
    // Test equals on different contexts with the same values
    assertTrue(context1.equals(context3));
    assertEquals(context1.hashCode(), context3.hashCode());
    // Test equals on different contexts
    assertFalse(context1.equals(root));
    assertNotEquals(context1.hashCode(), root.hashCode());
    assertFalse(context1.equals(context2));
    assertNotEquals(context1.hashCode(), context2.hashCode());
    assertFalse(context1.equals(context4));
    assertNotEquals(context1.hashCode(), context4.hashCode());
    assertFalse(root.equals(context1));
    assertNotEquals(root.hashCode(), context1.hashCode());
    assertFalse(context2.equals(context1));
    assertNotEquals(context2.hashCode(), context1.hashCode());
    assertFalse(context4.equals(context1));
    assertNotEquals(context4.hashCode(), context1.hashCode());
  }

  @ParameterizedTest
  @MethodSource("contextImplementations")
  void testToString(Context context) {
    String debugString = context.toString();
    assertNotNull(debugString, "Context string representation should not be null");
    assertTrue(
        debugString.contains(context.getClass().getSimpleName()),
        "Context string representation should contain implementation name");
  }

  @SuppressWarnings({"SimplifiableAssertion"})
  @Test
  void testInflation() {
    Context empty = root();

    Context one = empty.with(STRING_KEY, "unset").with(STRING_KEY, "one");
    Context two = one.with(BOOLEAN_KEY, false).with(BOOLEAN_KEY, true);
    Context three = two.with(FLOAT_KEY, 0.0f).with(FLOAT_KEY, 3.3f);

    assertNull(empty.get(STRING_KEY));
    assertNull(empty.get(BOOLEAN_KEY));
    assertNull(empty.get(FLOAT_KEY));
    assertNull(empty.get(LONG_KEY));

    assertEquals("one", one.get(STRING_KEY));
    assertNull(one.get(BOOLEAN_KEY));
    assertNull(one.get(FLOAT_KEY));
    assertNull(one.get(LONG_KEY));

    assertEquals("one", two.get(STRING_KEY));
    assertEquals(true, two.get(BOOLEAN_KEY));
    assertNull(two.get(FLOAT_KEY));
    assertNull(two.get(LONG_KEY));

    assertEquals("one", three.get(STRING_KEY));
    assertEquals(true, three.get(BOOLEAN_KEY));
    assertEquals(3.3f, three.get(FLOAT_KEY));
    assertNull(three.get(LONG_KEY));

    assertFalse(empty.equals(one));
    assertFalse(one.equals(two));
    assertFalse(two.equals(three));
    assertNotEquals(one.hashCode(), empty.hashCode());
    assertNotEquals(two.hashCode(), one.hashCode());
    assertNotEquals(three.hashCode(), two.hashCode());
  }
}
