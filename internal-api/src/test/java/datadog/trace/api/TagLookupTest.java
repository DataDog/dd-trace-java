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
      String[] actual = new String[names.size()];
      assertEquals(expectedFound, store.loadStrings(lookup, actual), "seed " + seed);
      assertArrayEquals(expected, actual, "seed " + seed);
      if (!store.mayHoldAny(lookup)) {
        assertEquals(0, expectedFound, "a false mayHoldAny must be definite (seed " + seed + ")");
      }
    }
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
