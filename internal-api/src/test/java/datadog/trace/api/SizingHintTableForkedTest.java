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
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Registry semantics for {@link SizingHintTable}, and how {@link DenseIdStore} sizes from and tunes
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

  /** Distinct known tag ids, in a stable order. */
  static final List<Long> KNOWN_IDS = new ArrayList<>();

  @BeforeAll
  static void checkSetup() throws IllegalAccessException {
    assertTrue(IdStoreFactory.Default.DENSE, "dense store must be on in this JVM");
    for (Field field : KnownTags.class.getFields()) {
      if (field.getType() != long.class) continue;
      long id = field.getLong(null);
      if (KnownTagCodec.isKnown(id) && !KNOWN_IDS.contains(id)) {
        KNOWN_IDS.add(id);
      }
    }
    assertTrue(KNOWN_IDS.size() > 24, "need enough known tags to exercise growth");
  }

  private static IdStore storeFor(SizingHint hint) {
    IdStore store = IdStore.create(null, hint);
    assertSame(DenseIdStore.class, store.getClass());
    return store;
  }

  private static Object field(IdStore store, String name) {
    try {
      Field f = DenseIdStore.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(store);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static int capacity(IdStore store) {
    char[] serials = (char[]) field(store, "serials");
    Object[] values = (Object[]) field(store, "values");
    assertEquals(serials.length, values.length, "dense arrays stay parallel");
    return serials.length;
  }

  /** Sets the first {@code n} known tags. */
  private static void fill(IdStore store, int n) {
    for (int i = 0; i < n; ++i) {
      store.set(KNOWN_IDS.get(i), "v" + i);
    }
    assertEquals(n, store.size());
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
    assertSame(
        SizingHintTable.hintFor("registry.stable", true),
        SizingHintTable.hintFor("registry.stable", true));
  }

  @Test
  void entryAndChildLanesAreIndependent() {
    SizingHint entry = SizingHintTable.hintFor("registry.twolane", true);
    SizingHint child = SizingHintTable.hintFor("registry.twolane", false);
    assertNotNull(entry);
    assertNotNull(child);
    assertNotSame(entry, child, "each lane holds its own hint for the same operation name");
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
    IdStore heavy = storeFor(another);
    fill(heavy, SizingHintTable.OVERFLOW_SEED + 4);
    heavy.recordSize();
    assertEquals(SizingHintTable.OVERFLOW_SEED, another.size);
  }

  @Test
  void sizingHintPresizesTheDenseStore() {
    SizingHint hint = SizingHintTable.hintFor("registry.presize", true);
    assertEquals(
        hint.size, capacity(storeFor(hint)), "presized at the hint's size, before a write");

    // Without a hint, a store starts on the shared empty arrays.
    assertEquals(0, capacity(storeFor(null)));
    assertEquals(0, capacity(IdStore.create(null)));
  }

  @Test
  void sizingHintFeedsAndTunesTheDenseStore() {
    SizingHint hint = SizingHintTable.hintFor("registry.tuning", true);
    assertEquals(SizingHintTable.SEED_SIZE, hint.size);

    int observed = SizingHintTable.SEED_SIZE + 3;
    IdStore store = storeFor(hint);
    fill(store, observed);
    store.recordSize();
    assertEquals(observed, hint.size, "hint self-tunes up to the observed high-water mark");

    // The next store of the operation is presized to fit, so it never grows.
    IdStore next = storeFor(hint);
    assertEquals(observed, capacity(next));
    fill(next, observed);
    assertEquals(observed, capacity(next));

    // A smaller later observation does not shrink the hint.
    IdStore smaller = storeFor(hint);
    fill(smaller, 1);
    smaller.recordSize();
    assertEquals(observed, hint.size, "recordSize never shrinks the hint");

    // A store without a hint records nothing.
    storeFor(null).recordSize();
  }

  @Test
  void fullArraysDoubleUpToTheSerialLimit() {
    // Presized small: 4 -> 8 -> 16
    IdStore store = storeFor(new SizingHint("registry.growth"));
    fill(store, 4);
    assertEquals(4, capacity(store));
    fill(store, 5);
    assertEquals(8, capacity(store));
    fill(store, 9);
    assertEquals(16, capacity(store));

    // Unhinted: first allocation at INIT_CAP, then doubling
    IdStore unhinted = storeFor(null);
    fill(unhinted, 1);
    assertEquals(DenseIdStore.INIT_CAP, capacity(unhinted));
    fill(unhinted, DenseIdStore.INIT_CAP + 1);
    assertEquals(2 * DenseIdStore.INIT_CAP, capacity(unhinted));

    // Growth never passes the serial limit, yet always makes room.
    IdStore all = storeFor(new SizingHint("registry.growth.all"));
    fill(all, KNOWN_IDS.size());
    assertTrue(capacity(all) <= KnownTagCodec.serialLimit());
    assertTrue(capacity(all) >= KNOWN_IDS.size());
  }
}
