package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.SizingHint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link SpanPrototype#sizingHint}'s per-lane cache and its fallback to the table. Hints exist only
 * with the dense store on, and the flag is read once into a static final, so this runs in its own
 * JVM with the flag set first. Each test uses its own operation names, since the table is
 * process-wide.
 */
class SpanPrototypeSizingHintForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "true");
  }

  @BeforeAll
  static void checkSetup() {
    assertNotNull(SizingHint.forOperation("setup.op", true), "dense store must be on in this JVM");
  }

  @Test
  void theOwnOperationNamesHintIsResolvedOncePerLane() {
    final SpanPrototype proto = SpanPrototype.builder().initOperationName("proto.op").build();

    final SizingHint entry = proto.sizingHint(true, "proto.op");
    final SizingHint child = proto.sizingHint(false, "proto.op");
    assertNotNull(entry);
    assertNotNull(child);
    assertNotSame(entry, child, "entry and child spans have separate lanes");

    // the table's hint, then cached: the same name as a UTF8BytesString gets the cached one
    assertSame(SizingHint.forOperation("proto.op", true), entry);
    assertSame(entry, proto.sizingHint(true, UTF8BytesString.create("proto.op")));
    assertSame(child, proto.sizingHint(false, "proto.op"));
  }

  /** Counts how often the name is converted to a String, which a table lookup does. */
  private static final class CountingName implements CharSequence {
    private final String name;
    int conversions;

    CountingName(String name) {
      this.name = name;
    }

    @Override
    public int length() {
      return name.length();
    }

    @Override
    public char charAt(int index) {
      return name.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return name.subSequence(start, end);
    }

    @Override
    public String toString() {
      conversions++;
      return name;
    }
  }

  @Test
  void aLaneLooksTheNameUpOnlyOnce() {
    final CountingName name = new CountingName("counted.op");
    final SpanPrototype proto = SpanPrototype.builder().initOperationName(name).build();

    final SizingHint first = proto.sizingHint(true, name);
    final int afterFirst = name.conversions;
    assertTrue(afterFirst > 0, "the first call looks the name up in the table");

    assertSame(first, proto.sizingHint(true, name));
    assertEquals(afterFirst, name.conversions, "a second call in the lane uses the cached hint");
  }

  @Test
  void anotherOperationNameGetsThatNamesHint() {
    final SpanPrototype proto = SpanPrototype.builder().initOperationName("own.op").build();

    final SizingHint other = proto.sizingHint(true, "other.op");
    assertNotNull(other);
    assertSame(SizingHint.forOperation("other.op", true), other);
    assertNotSame(proto.sizingHint(true, "own.op"), other, "unlike operations get their own hint");

    // a prototype with no operation name of its own always asks the table
    assertSame(
        SizingHint.forOperation("third.op", true), SpanPrototype.NONE.sizingHint(true, "third.op"));
  }
}
