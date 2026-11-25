package datadog.trace.bootstrap.jit;

import static datadog.trace.bootstrap.jit.Outcomes.assertSameOutcome;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Differential tests: each helper must behave exactly like the JDK method it replaces. */
class JdkRewritesTest {

  @Test
  void uuidToStringMatchesJdk() {
    Random random = new Random(99);
    long[] edges = {0, -1, 1, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789ABCDEFL};
    for (long most : edges) {
      for (long least : edges) {
        UUID uuid = new UUID(most, least);
        assertSameOutcome(uuid::toString, () -> JdkRewrites.uuidToString(uuid));
      }
    }
    for (int i = 0; i < 10_000; i++) {
      UUID uuid = new UUID(random.nextLong(), random.nextLong());
      assertSameOutcome(uuid::toString, () -> JdkRewrites.uuidToString(uuid));
    }
    UUID none = null;
    assertSameOutcome(() -> none.toString(), () -> JdkRewrites.uuidToString(none));
  }

  /** A member, a local and an anonymous class, plus arrays and a lambda. */
  static final class Member {}

  @Test
  void simpleNameMatchesJdk() {
    class Local {}
    Object anonymous = new Object() {};
    Supplier<String> lambda = () -> "x";
    Class<?>[] types = {
      String.class,
      int.class,
      int[].class,
      String[][].class,
      Map.Entry.class,
      AbstractMap.SimpleEntry.class,
      Member.class,
      Member[].class,
      Local.class,
      anonymous.getClass(),
      lambda.getClass(),
      JdkRewritesTest.class
    };
    for (int pass = 0; pass < 3; pass++) { // cached after the first
      for (Class<?> type : types) {
        assertSameOutcome(type::getSimpleName, () -> JdkRewrites.simpleName(type));
      }
    }
    Class<?> none = null;
    assertSameOutcome(() -> none.getSimpleName(), () -> JdkRewrites.simpleName(none));
  }
}
