package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TagLookupTest {

  static Stream<Arguments> stores() {
    return Stream.of(
        arguments(named("HashIdStore", (Function<IdStore, IdStore>) HashIdStore::new)),
        arguments(named("DenseIdStore", (Function<IdStore, IdStore>) DenseIdStore::new)));
  }

  private static List<Long> knownIds() throws IllegalAccessException {
    List<Long> ids = new ArrayList<>();
    for (java.lang.reflect.Field field : KnownTags.class.getFields()) {
      if (field.getType() == long.class) {
        long id = field.getLong(null);
        if (KnownTagCodec.isKnown(id) && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  /** The bulk read writes what a per-id load would, for random parent/child/tombstone states. */
  @ParameterizedTest
  @MethodSource("stores")
  void bulkReadMatchesPerTagLoads(Function<IdStore, IdStore> newStore) throws Exception {
    List<Long> ids = knownIds();
    for (int seed = 0; seed < 200; seed++) {
      Random random = new Random(seed);
      IdStore parent = newStore.apply(null);
      for (long id : ids) {
        if (random.nextInt(8) == 0) {
          parent.store(id, "parent-" + KnownTagCodec.nameOf(id));
        }
      }
      IdStore store = newStore.apply(random.nextBoolean() ? parent : null);
      for (long id : ids) {
        int roll = random.nextInt(12);
        if (roll == 0) {
          store.store(id, "local-" + KnownTagCodec.nameOf(id));
        } else if (roll == 1) {
          store.remove(id); // hides an inherited value, or does nothing
        } else if (roll == 2) {
          store.store(id, 42L);
        }
      }

      List<String> names = new ArrayList<>();
      for (long id : ids) {
        if (random.nextInt(3) == 0) {
          names.add(KnownTagCodec.nameOf(id));
        }
      }
      names.add("custom.tag." + seed); // a custom name: the store leaves it to the caller
      Collections.shuffle(names, random);
      TagLookup lookup = TagLookup.of(names.toArray(new String[0]));

      String[] expected = new String[names.size()];
      int expectedFound = 0;
      for (int i = 0; i < names.size(); i++) {
        long id = KnownTagCodec.keyOf(names.get(i));
        Object value = id == 0 ? null : store.load(id);
        if (value != null) {
          expected[i] = value.toString();
          expectedFound++;
        }
      }
      String[] actual = store.loadStrings(lookup, null);
      // allocated only on a hit: no tags found, no array
      assertArrayEquals(expectedFound == 0 ? null : expected, actual, "seed " + seed);
      if (!store.mayHoldAny(lookup)) {
        assertEquals(0, expectedFound, "a false mayHoldAny must be definite (seed " + seed + ")");
      }
    }
  }

  /**
   * Small lookups over small stores, so the stores scan their own entries against the lookup rather
   * than probing for each name: the scan must agree with per-id loads, including read-through to a
   * parent and tombstones hiding inherited values.
   */
  @ParameterizedTest
  @MethodSource("stores")
  void scanningReadMatchesPerTagLoads(Function<IdStore, IdStore> newStore) throws Exception {
    List<Long> ids = knownIds();
    for (int seed = 0; seed < 300; seed++) {
      Random random = new Random(seed);
      List<Long> pool = new ArrayList<>(ids);
      Collections.shuffle(pool, random);
      List<Long> chosen = pool.subList(0, 40); // the lookup's names, and the only ids stored
      IdStore parent = newStore.apply(null);
      for (long id : chosen) {
        if (random.nextInt(6) == 0) {
          parent.store(id, "parent-" + KnownTagCodec.nameOf(id));
        }
      }
      IdStore store = newStore.apply(random.nextBoolean() ? parent : null);
      for (long id : chosen) {
        int roll = random.nextInt(10);
        if (roll == 0) {
          store.store(id, "local-" + KnownTagCodec.nameOf(id));
        } else if (roll == 1) {
          store.remove(id);
        } else if (roll == 2) {
          store.store(id, 7L);
        }
      }
      String[] names = new String[chosen.size()];
      for (int i = 0; i < names.length; i++) {
        names[i] = KnownTagCodec.nameOf(chosen.get(i));
      }
      TagLookup lookup = TagLookup.of(names);
      String[] expected = new String[names.length];
      int expectedFound = 0;
      for (int i = 0; i < names.length; i++) {
        Object value = store.load(chosen.get(i));
        if (value != null) {
          expected[i] = value.toString();
          expectedFound++;
        }
      }
      assertArrayEquals(
          expectedFound == 0 ? null : expected, store.loadStrings(lookup, null), "seed " + seed);
    }
  }

  /**
   * Names that share an id, a second spelling of one tag or a repeated name, each get the value, on
   * the scan path (fewer entries than known names) and the probe path alike.
   */
  @ParameterizedTest
  @MethodSource("stores")
  void namesSharingAnIdAllGetTheValue(Function<IdStore, IdStore> newStore) {
    assertEquals(KnownTagCodec.keyOf("http.method"), KnownTagCodec.keyOf("http.request.method"));
    TagLookup lookup = TagLookup.of("http.method", "http.request.method", "http.method");
    String[] expected = {"GET", "GET", "GET"};

    IdStore scanned = newStore.apply(null);
    scanned.store(KnownTags.HTTP_METHOD, "GET"); // 1 entry < 3 known names: scans
    assertArrayEquals(expected, scanned.loadStrings(lookup, null));

    IdStore probed = newStore.apply(null);
    probed.store(KnownTags.HTTP_METHOD, "GET");
    probed.store(KnownTags.PEER_SERVICE, "a");
    probed.store(KnownTags.DB_INSTANCE, "b"); // 3 entries, not fewer than 3 names: probes
    assertArrayEquals(expected, probed.loadStrings(lookup, null));
  }

  @Test
  void anEmptyDenseStoreRulesOutTheWholeLookup() throws Exception {
    List<Long> ids = knownIds();
    String[] names = new String[ids.size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = KnownTagCodec.nameOf(ids.get(i));
    }
    assertFalse(new DenseIdStore(null).mayHoldAny(TagLookup.of(names)));
  }

  @Test
  void aDenseStoreHoldingOneOfTheTagsMayHoldTheLookup() {
    DenseIdStore store = new DenseIdStore(null);
    store.store(KnownTags.PEER_SERVICE, "billing");
    assertTrue(store.mayHoldAny(TagLookup.of("db.instance", "peer.service")));
  }

  @Test
  void customNamesAreRecordedForTheCaller() {
    TagLookup lookup = TagLookup.of("peer.service", "my.custom.tag");
    assertTrue(lookup.hasCustom());
    assertEquals(1, lookup.customCount());
    assertEquals("my.custom.tag", lookup.name(lookup.customIndex(0)));
  }
}
