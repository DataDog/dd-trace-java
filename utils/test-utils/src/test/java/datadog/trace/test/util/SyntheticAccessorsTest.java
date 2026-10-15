package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.perfcontract.NoSyntheticAccessors;
import datadog.perfcontract.Singleton;
import datadog.perfcontract.SuppressPerfContract;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
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
    assertTrue(
        accessors.stream()
            .anyMatch(a -> a.endsWith("-> datadog.trace.test.util.WithAccessors.secret")),
        accessors.toString());
    assertThrows(AssertionError.class, () -> SyntheticAccessors.assertNone(WithAccessors.class));
  }

  @Test
  void acceptsPackagePrivateMembersAndLambdas() {
    SyntheticAccessors.assertNone(WithoutAccessors.class);
  }

  @Test
  void acceptsAccessorsToSuppressedMembers() {
    SyntheticAccessors.assertNone(WithSuppressedAccessors.class);
  }

  @Test
  void acceptsSuppressionsThroughAMetaAnnotation() {
    SyntheticAccessors.assertNone(WithMetaSuppressedAccessor.class);
  }

  @Test
  void rejectsASuppressionWithABlankReason() {
    List<String> accessors = SyntheticAccessors.find(WithBlankReasonSuppression.class);

    assertEquals(1, accessors.size(), accessors.toString());
    assertTrue(accessors.get(0).endsWith("(its @SuppressPerfContract has a blank reason)"));
  }

  @Test
  void rejectsASuppressionOfAnotherContract() {
    List<String> accessors = SyntheticAccessors.find(WithOtherContractSuppression.class);

    assertEquals(1, accessors.size(), accessors.toString());
    assertTrue(
        accessors
            .get(0)
            .endsWith("(its @SuppressPerfContract does not name NoSyntheticAccessors)"));
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

final class WithSuppressedAccessors {
  @SuppressPerfContract(value = NoSyntheticAccessors.class, reason = "read once, at setup")
  private int secret = 42;

  Inner newInner() {
    return new Inner();
  }

  final class Inner {
    @SuppressPerfContract(value = NoSyntheticAccessors.class, reason = "built once, at setup")
    private Inner() {}

    int read() {
      return secret;
    }
  }
}

/** A reusable suppression, applied through a meta-annotation. */
@SuppressPerfContract(value = NoSyntheticAccessors.class, reason = "off the hot path")
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
@interface ColdPath {}

final class WithMetaSuppressedAccessor {
  @ColdPath private int secret = 42;

  final class Inner {
    int read() {
      return secret;
    }
  }
}

final class WithBlankReasonSuppression {
  @SuppressPerfContract(value = NoSyntheticAccessors.class, reason = " ")
  private int secret = 42;

  final class Inner {
    int read() {
      return secret;
    }
  }
}

final class WithOtherContractSuppression {
  @SuppressPerfContract(value = Singleton.class, reason = "not this contract")
  private int secret = 42;

  final class Inner {
    int read() {
      return secret;
    }
  }
}
