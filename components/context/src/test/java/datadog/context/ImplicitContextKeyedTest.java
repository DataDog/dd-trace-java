package datadog.context;

import static datadog.context.Context.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

class ImplicitContextKeyedTest {
  /** This class demonstrate how values can hide their context keys. */
  static class ValueWithKey implements ImplicitContextKeyed {
    /** The private key used to store and retrieve context value. */
    private static final ContextKey<ValueWithKey> HIDDEN_KEY = ContextKey.named("hidden-key");

    @Override
    public Context storeInto(@Nonnull Context context) {
      return context.with(HIDDEN_KEY, this);
    }

    @Nullable
    public static ValueWithKey from(@Nonnull Context context) {
      return context.get(HIDDEN_KEY);
    }
  }

  @Test
  void testImplicitKey() {
    // Setup context
    Context root = root();
    ValueWithKey valueWithKey = new ValueWithKey();
    Context context = root.with(valueWithKey);
    assertNull(ValueWithKey.from(root), "No value expected to be extracted from root context");
    assertEquals(
        valueWithKey, ValueWithKey.from(context), "Expected to retrieve the implicit keyed value");
  }
}
