package datadog.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ContextTest {
  static final ContextKey<String> STRING_KEY = ContextKey.named("string-key");
  static final ContextKey<Boolean> BOOLEAN_KEY = ContextKey.named("boolean-key");

  @BeforeAll
  static void setup() {
    DefaultContextBinder.register();
  }

  @Test
  void testEmpty() {
    // Test empty is always the same
    Context empty = Context.empty();
    assertEquals(empty, Context.empty());
    // Test empty is not mutated
    String stringValue = "value";
    empty.with(STRING_KEY, stringValue);
    assertEquals(empty, Context.empty());
  }

  @Test
  void testWith() {
    Context empty = Context.empty();
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
    //noinspection DataFlowIssue
    assertDoesNotThrow(() -> empty.with(null, "test"));
  }

  @Test
  void testGet() {
    // Setup context
    Context empty = Context.empty();
    String value = "value";
    Context context = empty.with(STRING_KEY, value);
    // Test null key handling
    //noinspection DataFlowIssue
    assertNull(context.get(null));
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
    Context empty = Context.empty();
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
}
