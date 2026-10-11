package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Registry semantics for {@link SizingHintTable}, and how {@link DenseTagMap} sizes from and tunes
 * a {@link SizingHint}. Hints are only minted with the dense store on, and the flag is read once
 * into a static final, so this runs in its own JVM ({@code ForkedTest}) with the flag set first.
 *
 * <p>The table is process-wide static state; each test uses distinct operation names so tests don't
 * interfere. The overflow test fills the child lane, so no other test here uses that lane expecting
 * a fresh hint.
 */
class SizingHintTableForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "true");
  }

  /** Distinct known tag ids that each add one dense entry, in a stable order. */
  static final List<Long> KNOWN_IDS = new ArrayList<>();

  @BeforeAll
  static void checkSetup() throws IllegalAccessException {
    assertTrue(TagMap.DenseStore.ENABLED, "dense store must be on in this JVM");
    Set<Long> ids = new LinkedHashSet<>();
    for (Field field : KnownTags.class.getFields()) {
      if (field.getType() != long.class) continue;
      long id = field.getLong(null);
      if (!KnownTagCodec.isKnown(id)) continue;
      String name = KnownTagCodec.nameOf(id);
      if (name == null || KnownTagCodec.keyOf(name) != id) continue; // skip shared names
      ids.add(id);
    }
    // keep only ids that really add a dense entry (no relabeling of another id)
    TagMap probe = TagMap.create();
    for (long id : ids) {
      int before = knownCount(probe);
      probe.set(id, "v");
      if (knownCount(probe) == before + 1) {
        KNOWN_IDS.add(id);
      }
    }
    assertTrue(KNOWN_IDS.size() > 24, "need enough known tags to exercise growth");
  }

  private static Object field(TagMap map, String name) {
    try {
      Field f = DenseTagMap.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(map);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static int knownCount(TagMap map) {
    return (Integer) field(map, "knownCount");
  }

  private static int knownCapacity(TagMap map) {
    long[] ids = (long[]) field(map, "knownIds");
    Object[] values = (Object[]) field(map, "knownValues");
    assertEquals(ids.length, values.length, "dense arrays stay parallel");
    return ids.length;
  }

  /** Adds the first {@code n} known tags. */
  private static void fill(TagMap map, int n) {
    for (int i = 0; i < n; ++i) {
      map.set(KNOWN_IDS.get(i), "v" + i);
    }
    assertEquals(n, knownCount(map));
  }

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
    assertEquals("registry.fresh", hint.key());
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
    // A hint minted before the lane fills keeps resolving to itself afterwards.
    SizingHint early = SizingHintTable.hintFor("registry.flood.early", false);
    assertFalse(early.capped);

    // Fill the child lane past its budget; a capped shared hint must appear.
    SizingHint firstOverflow = null;
    for (int i = 0; i <= SizingHintTable.CARDINALITY_LIMIT && firstOverflow == null; i++) {
      SizingHint hint = SizingHintTable.hintFor("registry.flood." + i, false);
      assertNotNull(hint);
      if (hint.capped) {
        firstOverflow = hint;
      }
    }
    assertNotNull(firstOverflow, "lane collapses to a capped overflow hint once full");
    assertEquals(SizingHintTable.OVERFLOW_SEED, firstOverflow.size);

    // Every further over-budget name shares that same capped instance...
    SizingHint another = SizingHintTable.hintFor("registry.flood.after", false);
    assertTrue(another.capped);
    assertSame(firstOverflow, another);
    // ...existing hints still resolve (the cap blocks only creation)...
    assertSame(early, SizingHintTable.hintFor("registry.flood.early", false));
    // ...and the entry lane has its own budget.
    assertFalse(SizingHintTable.hintFor("registry.flood.after", true).capped);

    // A capped hint is never tuned: a heavy span sharing it does not grow it.
    TagMap heavy = TagMap.create(another);
    fill(heavy, SizingHintTable.OVERFLOW_SEED + 4);
    heavy.recordSize(another);
    assertEquals(SizingHintTable.OVERFLOW_SEED, another.size);
  }

  @Test
  void sizingHintPresizesTheDenseStore() {
    SizingHint hint = SizingHintTable.hintFor("registry.presize", true);
    TagMap map = TagMap.create(hint);
    assertSame(DenseTagMap.class, map.getClass());
    assertEquals(hint.size, knownCapacity(map), "presized at the hint's size, before any write");

    TagMap child = TagMap.createFromParent(TagMap.create().freeze(), hint);
    assertEquals(hint.size, knownCapacity(child));

    // Without a hint, a map starts on the shared empty arrays.
    assertEquals(0, knownCapacity(TagMap.create((SizingHint) null)));
    assertEquals(0, knownCapacity(TagMap.create()));
  }

  @Test
  void sizingHintFeedsAndTunesTheDenseStore() {
    SizingHint hint = SizingHintTable.hintFor("registry.tuning", true);
    assertEquals(SizingHintTable.SEED_SIZE, hint.size);

    int observed = SizingHintTable.SEED_SIZE + 3;
    TagMap map = TagMap.create(hint);
    fill(map, observed);
    map.recordSize(hint);
    assertEquals(observed, hint.size, "hint self-tunes up to the observed known-tag high-water");

    // The next map of the operation is presized to fit, so it never grows.
    TagMap next = TagMap.create(hint);
    assertEquals(observed, knownCapacity(next));
    fill(next, observed);
    assertEquals(observed, knownCapacity(next));

    // A smaller later observation does not shrink the hint.
    TagMap smaller = TagMap.create(hint);
    fill(smaller, 1);
    smaller.recordSize(hint);
    assertEquals(observed, hint.size, "recordSize never shrinks the hint");

    // A null hint is a no-op.
    smaller.recordSize(null);
  }

  @Test
  void fullDenseArraysDoubleUpToTheSerialLimit() {
    // Presized small: 4 -> 8 -> 16
    SizingHint hint = new SizingHint("registry.growth");
    assertEquals(4, hint.size);
    TagMap map = TagMap.create(hint);
    fill(map, 4);
    assertEquals(4, knownCapacity(map));
    fill(map, 5);
    assertEquals(8, knownCapacity(map));
    fill(map, 9);
    assertEquals(16, knownCapacity(map));

    // Unhinted: first allocation at the default 12, then 24
    TagMap unhinted = TagMap.create();
    fill(unhinted, 1);
    assertEquals(12, knownCapacity(unhinted));
    fill(unhinted, 13);
    assertEquals(24, knownCapacity(unhinted));

    // Growth never passes the serial limit, yet always makes room.
    TagMap all = TagMap.create(new SizingHint("registry.growth.all"));
    fill(all, KNOWN_IDS.size());
    assertTrue(knownCapacity(all) <= KnownTagCodec.serialLimit());
    assertTrue(knownCapacity(all) >= KNOWN_IDS.size());
  }
}
