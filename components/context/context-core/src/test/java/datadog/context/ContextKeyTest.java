package datadog.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContextKeyTest {
  @BeforeAll
  static void setup() {
    DefaultContextBinder.register();
  }

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
    ContextKey<String> key1 = ContextKey.named("same-key");
    ContextKey<String> key2 = ContextKey.named("same-key");
    String value = "value";
    Context context = Context.empty().with(key1, value);
    assertEquals(value, context.get(key1));
    assertNull(context.get(key2));
  }
}
