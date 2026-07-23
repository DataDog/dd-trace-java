package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import org.junit.jupiter.api.Test;

/**
 * Registry semantics for {@link SizingHintTable}. The table is process-wide static state; each test
 * uses distinct operation names so tests don't interfere (the only shared, cumulative state is the
 * per-lane cardinality counter, which the overflow test drives past its bound with its own names).
 */
class SizingHintTableTest {

  @Test
  void nullOperationNameGetsNoHint() {
    assertNull(SizingHintTable.hintFor(null, true));
    assertNull(SizingHintTable.hintFor(null, false));
  }

  @Test
  void nonStringCharSequenceIsKeyedByContent() {
    // Operation names are commonly UTF8BytesString, not String; a content-equal name must resolve
    // to the same hint as its String form (keyed by toString(), which is O(1) for these types).
    SizingHint viaString = SizingHintTable.hintFor("registry.utf8", true);
    CharSequence utf8 = UTF8BytesString.create("registry.utf8");
    assertNotNull(viaString);
    assertSame(viaString, SizingHintTable.hintFor(utf8, true));
  }

  @Test
  void freshHintIsSeededAndUncapped() {
    SizingHint hint = SizingHintTable.hintFor("registry.fresh", true);
    assertNotNull(hint);
    assertEquals(SizingHintTable.SEED_SIZE, hint.size);
    assertFalse(hint.capped);
    assertEquals("registry.fresh", hint.label);
  }

  @Test
  void sameOperationAndLaneReturnsSameInstance() {
    SizingHint a = SizingHintTable.hintFor("registry.stable", true);
    SizingHint b = SizingHintTable.hintFor("registry.stable", true);
    assertSame(a, b);
  }

  @Test
  void entryAndChildLanesAreIndependent() {
    SizingHint entry = SizingHintTable.hintFor("registry.twolane", true);
    SizingHint child = SizingHintTable.hintFor("registry.twolane", false);
    assertNotNull(entry);
    assertNotNull(child);
    assertNotSame(entry, child, "each lane holds its own hint for the same operation name");
    // ...and each lane is internally stable.
    assertSame(entry, SizingHintTable.hintFor("registry.twolane", true));
    assertSame(child, SizingHintTable.hintFor("registry.twolane", false));
  }

  @Test
  void beyondCardinalityBudgetSharesACappedOverflowHint() {
    // Push far more distinct names than any lane's budget; a capped shared hint must appear.
    SizingHint firstOverflow = null;
    for (int i = 0; i < 4096 && firstOverflow == null; i++) {
      SizingHint hint = SizingHintTable.hintFor("registry.flood." + i, true);
      assertNotNull(hint);
      if (hint.capped) {
        firstOverflow = hint;
      }
    }
    assertNotNull(firstOverflow, "lane eventually collapses to a capped overflow hint");
    assertEquals(SizingHintTable.OVERFLOW_SEED, firstOverflow.size);

    // Every further over-budget name shares that same capped instance.
    SizingHint another = SizingHintTable.hintFor("registry.flood.after", true);
    assertTrue(another.capped);
    assertSame(firstOverflow, another);
  }

  @Test
  void sizingHintFeedsAndTunesTheDenseStore() {
    KnownTags.init(); // register the real allocation-free resolver so known tags route dense
    SizingHint hint = SizingHintTable.hintFor("registry.tuning", true);
    assertEquals(SizingHintTable.SEED_SIZE, hint.size);

    TagMap map = TagMap.create(hint);
    map.set(DDTags.BASE_SERVICE, "svc");
    map.set(datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT, "comp");
    map.set(datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND, "server");

    map.recordSize(hint);
    assertEquals(3, hint.size, "hint self-tunes up to the observed known-tag high-water mark");

    // Monotonic-max: a smaller later observation does not shrink the hint.
    TagMap smaller = TagMap.create(hint);
    smaller.set(DDTags.BASE_SERVICE, "svc");
    smaller.recordSize(hint);
    assertEquals(3, hint.size, "recordSize never shrinks the hint");
  }
}
