package datadog.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

class ContextTest {
  static final ContextKey<String> STRING_KEY = ContextKey.named("string-key");
  static final ContextKey<Boolean> BOOLEAN_KEY = ContextKey.named("boolean-key");

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
    // Test equals on self
    assertTrue(empty.equals(empty));
    assertTrue(context1.equals(context1));
    // Test equals on null
    assertFalse(context1.equals(null));
    // Test equals on different object type
    assertFalse(context1.equals("value"));
    // Test equals on different contexts with the same values
    assertTrue(context1.equals(context3));
    assertEquals(context1.hashCode(), context3.hashCode());
    // Test equals on different contexts
    assertFalse(context1.equals(empty));
    assertFalse(context1.equals(context2));
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
}
