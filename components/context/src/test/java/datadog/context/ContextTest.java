package datadog.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

class ContextTest {
  static final ContextKey<String> STRING_KEY = ContextKey.named("string-key");
  static final ContextKey<Boolean> BOOLEAN_KEY = ContextKey.named("boolean-key");
  static final ContextKey<Float> FLOAT_KEY = ContextKey.named("float-key");
  static final ContextKey<Long> LONG_KEY = ContextKey.named("long-key");

  // demonstrate how values can hide their context keys
  static class ValueWithKey implements ImplicitContextKeyed {
    static final ContextKey<ValueWithKey> HIDDEN_KEY = ContextKey.named("hidden-key");

    @Override
    public Context storeInto(Context context) {
      return context.with(HIDDEN_KEY, this);
    }

    @Nullable
    public static ValueWithKey from(Context context) {
      return context.get(HIDDEN_KEY);
    }
  }

  @Test
  void testEmpty() {
    // Test empty is always the same
    Context empty = Context.root();
    assertEquals(empty, Context.root());
    // Test empty is not mutated
    String stringValue = "value";
    empty.with(STRING_KEY, stringValue);
    assertEquals(empty, Context.root());
  }

  @Test
  void testWith() {
    Context empty = Context.root();
    // Test accessing non-set value
    assertNull(empty.get(STRING_KEY));
    // Test retrieving value
    String stringValue = "value";
    Context context1 = empty.with(STRING_KEY, stringValue);
    assertEquals(stringValue, context1.get(STRING_KEY));
    // Test overriding value
    String stringValue2 = "value2";
    Context context2 = context1.with(STRING_KEY, stringValue2);
    assertEquals(stringValue2, context2.get(STRING_KEY));
    // Test clearing value
    Context context3 = context2.with(STRING_KEY, null);
    assertNull(context3.get(STRING_KEY));
    // Test null key handling
    assertThrows(NullPointerException.class, () -> empty.with(null, "test"));
  }

  @Test
  void testGet() {
    // Setup context
    Context empty = Context.root();
    String value = "value";
    Context context = empty.with(STRING_KEY, value);
    // Test null key handling
    assertThrows(NullPointerException.class, () -> context.get(null));
    // Test unset key
    assertNull(context.get(BOOLEAN_KEY));
    // Test set key
    assertEquals(value, context.get(STRING_KEY));
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
    Context empty = Context.root();
    Context context1 = empty.with(STRING_KEY, "value");
    Context context2 = empty.with(STRING_KEY, "value    ");
    Context context3 = empty.with(STRING_KEY, "value    ".trim());
    Context context4 = empty.with(STRING_KEY, "value").with(BOOLEAN_KEY, true);
    // Test equals on self
    assertTrue(empty.equals(empty));
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
    assertFalse(context1.equals(empty));
    assertNotEquals(context1.hashCode(), empty.hashCode());
    assertFalse(context1.equals(context2));
    assertNotEquals(context1.hashCode(), context2.hashCode());
    assertFalse(context1.equals(context4));
    assertNotEquals(context1.hashCode(), context4.hashCode());
    assertFalse(empty.equals(context1));
    assertNotEquals(empty.hashCode(), context1.hashCode());
    assertFalse(context2.equals(context1));
    assertNotEquals(context2.hashCode(), context1.hashCode());
    assertFalse(context4.equals(context1));
    assertNotEquals(context4.hashCode(), context1.hashCode());
  }

  @Test
  void testImplicitKey() {
    // Setup context
    Context empty = Context.root();
    ValueWithKey valueWithKey = new ValueWithKey();
    Context context = empty.with(valueWithKey);
    assertNull(ValueWithKey.from(empty));
    assertEquals(valueWithKey, ValueWithKey.from(context));
  }

  @SuppressWarnings({"SimplifiableAssertion"})
  @Test
  void testInflation() {
    Context empty = Context.root();

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
