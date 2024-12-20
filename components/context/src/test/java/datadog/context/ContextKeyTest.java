package datadog.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContextKeyTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "key"})
  void testConstructor(String name) {
    ContextKey<String> key = ContextKey.named(name);
    assertNotNull(key);
    assertEquals(name, key.toString());
  }

  @Test
  void testKeyNameCollision() {
    ContextKey<String> key1 = ContextKey.named("same-name");
    ContextKey<String> key2 = ContextKey.named("same-name");
    assertNotEquals(key1, key2);
    String value = "value";
    Context context = Context.root().with(key1, value);
    assertEquals(value, context.get(key1));
    assertNull(context.get(key2));
  }

  @SuppressWarnings({
    "EqualsWithItself",
    "SimplifiableAssertion",
    "ConstantValue",
    "EqualsBetweenInconvertibleTypes"
  })
  @Test
  void testEqualsAndHashCode() {
    ContextKey<String> key1 = ContextKey.named("same-name");
    ContextKey<String> key2 = ContextKey.named("same-name");
    // Test equals on self
    assertTrue(key1.equals(key1));
    assertEquals(key1.hashCode(), key1.hashCode());
    // Test equals on null
    assertFalse(key1.equals(null));
    // Test equals on different object type
    assertFalse(key1.equals("value"));
    // Test equals on different keys with the same name
    assertFalse(key1.equals(key2));
    assertNotEquals(key1.hashCode(), key2.hashCode());
  }
}
