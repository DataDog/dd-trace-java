package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

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
