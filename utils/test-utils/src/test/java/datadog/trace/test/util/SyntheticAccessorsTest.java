package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SyntheticAccessorsTest {
  @Test
  void findsAccessorsForPrivateMembersUsedAcrossTheNest() {
    List<String> accessors = SyntheticAccessors.find(WithAccessors.class);

    // WithAccessors.secret is read by Inner, and Inner's private constructor is called by
    // WithAccessors: one access$ method and one synthetic constructor.
    assertEquals(2, accessors.size(), accessors.toString());
    assertTrue(
        accessors.stream().anyMatch(a -> a.contains("WithAccessors.access$")),
        accessors.toString());
    assertTrue(
        accessors.stream().anyMatch(a -> a.contains("WithAccessors$Inner.<init>")),
        accessors.toString());
    assertThrows(AssertionError.class, () -> SyntheticAccessors.assertNone(WithAccessors.class));
  }

  @Test
  void acceptsPackagePrivateMembersAndLambdas() {
    SyntheticAccessors.assertNone(WithoutAccessors.class);
  }

  @Test
  void readsNestsFromJars() {
    // Assertions comes from the junit-jupiter-api jar, so this lists its nest from jar entries.
    assertTrue(Assertions.class.getResource("Assertions.class").getProtocol().equals("jar"));
    SyntheticAccessors.find(Assertions.class);
  }

  @Test
  void rejectsNestedClasses() {
    assertThrows(
        IllegalArgumentException.class, () -> SyntheticAccessors.find(WithAccessors.Inner.class));
  }
}

final class WithAccessors {
  private int secret = 42;

  Inner newInner() {
    return new Inner();
  }

  final class Inner {
    private Inner() {}

    int read() {
      return secret;
    }
  }
}

final class WithoutAccessors {
  int visible = 42;

  Supplier<Integer> supplier() {
    return () -> visible;
  }

  final class Inner {
    Inner() {}

    int read() {
      return visible;
    }
  }
}
