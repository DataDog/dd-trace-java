package datadog.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
