package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMapFuzzTest.MapAction;
import datadog.trace.api.TagMapFuzzTest.TestCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fuzz test for the dense store under a LIVE resolver, across three key regimes. Reuses {@link
 * TagMapFuzzTest}'s oracle machinery ({@code test(TestCase)} replays a random action sequence
 * against a {@code HashMap}, verifying each step + {@code checkIntegrity}).
 *
 * <p>Uses a synthetic prefix resolver ({@code known-N} -> stored / dense, anything else -> bucket)
 * rather than the real {@link KnownTags}: it gives an UNBOUNDED known key space, so the dense array
 * actually grows past its initial capacity and the linear scan gets long, and it lets each test pin
 * the known/custom ratio. The three regimes exercise paths the mixed run alone would miss:
 *
 * <ul>
 *   <li><b>known-only</b> — the all-dense map (dense growth, dense-only putAll/copy/clear/iterate
 *       with no bucket phase, knownCount-only size).
 *   <li><b>custom-only</b> — confirms the dense branches stay inert when nothing resolves, even
 *       with a resolver registered.
 *   <li><b>mixed</b> — both regions and their interaction.
 * </ul>
 *
 * <p>Forked (isolated JVM) because resolver registration is a global static with no un-register.
 */
class TagMapDenseFuzzForkedTest {
  static final int SINGLE_MAP_CASES = 1500;
  static final int MERGE_CASES = 400;
  static final int MAX_ACTIONS = 40;
  static final int MIN_ACTIONS = 8;

  // unbounded synthetic key spaces — large enough to grow the dense array past cap-8 several times
  static final int KNOWN_SPACE = 48;
  static final int CUSTOM_SPACE = 48;

  enum Regime {
    KNOWN_ONLY,
    CUSTOM_ONLY,
    MIXED
  }

  /**
   * Synthetic resolver: {@code known-N} -> stored id (serial = FIRST_STORED_SERIAL + N); else 0.
   */
  static final KnownTagCodec.Resolver FUZZ_RESOLVER =
      new KnownTagCodec.Resolver() {
        @Override
        public long keyOf(String name) {
          if (name.startsWith("known-")) {
            int n = Integer.parseInt(name.substring("known-".length()));
            return KnownTagCodec.tagId(KnownTagCodec.FIRST_STORED_SERIAL + n, n);
          }
          return 0L;
        }

        @Override
        public String nameOf(long tagId) {
          int serial = KnownTagCodec.globalSerial(tagId);
          return serial >= KnownTagCodec.FIRST_STORED_SERIAL
              ? "known-" + (serial - KnownTagCodec.FIRST_STORED_SERIAL)
              : null;
        }

        @Override
        public int slotCount() {
          return 0; // positional unused
        }
      };

  @BeforeAll
  static void registerResolver() {
    KnownTagCodec.register(FUZZ_RESOLVER);
    assertTrue(KnownTagCodec.isActive(), "resolver must be live");
    assertTrue(KnownTagCodec.isStored(KnownTagCodec.keyOf("known-0")), "known- routes dense");
    assertFalse(
        KnownTagCodec.isStored(KnownTagCodec.keyOf("custom-0")), "custom- stays in buckets");
    // round-trip the synthetic encoding
    long id = KnownTagCodec.keyOf("known-7");
    assertTrue("known-7".equals(KnownTagCodec.nameOf(id)), "name<->id round-trips");
  }

  @Test
  void knownOnlyFuzz() {
    runRegime(Regime.KNOWN_ONLY);
  }

  @Test
  void customOnlyFuzz() {
    runRegime(Regime.CUSTOM_ONLY);
  }

  @Test
  void mixedFuzz() {
    runRegime(Regime.MIXED);
  }

  private static void runRegime(Regime regime) {
    for (int i = 0; i < SINGLE_MAP_CASES; ++i) {
      TagMapFuzzTest.test(generateTest(regime));
    }
    for (int i = 0; i < MERGE_CASES; ++i) {
      TagMap mapA = TagMapFuzzTest.test(generateTest(regime));
      TagMap mapB = TagMapFuzzTest.test(generateTest(regime));

      HashMap<String, Object> hashA = new HashMap<>(mapA);
      HashMap<String, Object> hashB = new HashMap<>(mapB);

      mapA.putAll(mapB);
      hashA.putAll(hashB);

      TagMapFuzzTest.assertMapEquals(hashA, mapA);
    }
  }

  // --- action generation (mirrors TagMapFuzzTest.randomAction, regime-driven key pool) ---

  private static TestCase generateTest(Regime regime) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int numActions = r.nextInt(MAX_ACTIONS - MIN_ACTIONS) + MIN_ACTIONS;
    List<MapAction> actions = new ArrayList<>(numActions);
    for (int i = 0; i < numActions; ++i) {
      actions.add(randomAction(regime));
    }
    return new TestCase(actions);
  }

  private static MapAction randomAction(Regime regime) {
    switch (randomChoice(0.02, 0.1, 0.2)) {
      case 0:
        return TagMapFuzzTest.clear();
      case 1:
        return choose(
            () -> TagMapFuzzTest.putAll(randomKeysAndValues(regime)),
            () -> TagMapFuzzTest.putAllTagMap(randomKeysAndValues(regime)),
            () -> TagMapFuzzTest.putAllLedger(randomKeysAndValues(regime)));
      case 2:
        return choose(
            () -> TagMapFuzzTest.remove(randomKey(regime)),
            () -> TagMapFuzzTest.removeLight(randomKey(regime)),
            () -> TagMapFuzzTest.getAndRemove(randomKey(regime)));
      default:
        return choose(
            () -> TagMapFuzzTest.put(randomKey(regime), randomValue()),
            () -> TagMapFuzzTest.set(randomKey(regime), randomValue()),
            () -> TagMapFuzzTest.getAndSet(randomKey(regime), randomValue()));
    }
  }

  private static String randomKey(Regime regime) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    boolean known;
    switch (regime) {
      case KNOWN_ONLY:
        known = true;
        break;
      case CUSTOM_ONLY:
        known = false;
        break;
      default:
        known = r.nextBoolean();
    }
    return known ? "known-" + r.nextInt(KNOWN_SPACE) : "custom-" + r.nextInt(CUSTOM_SPACE);
  }

  private static String randomValue() {
    return "values-" + ThreadLocalRandom.current().nextInt();
  }

  private static String[] randomKeysAndValues(Regime regime) {
    int numEntries = ThreadLocalRandom.current().nextInt(KNOWN_SPACE + CUSTOM_SPACE);
    String[] keysAndValues = new String[numEntries << 1];
    for (int i = 0; i < keysAndValues.length; i += 2) {
      keysAndValues[i] = randomKey(regime);
      keysAndValues[i + 1] = randomValue();
    }
    return keysAndValues;
  }

  private static int randomChoice(double... proportions) {
    double selector = ThreadLocalRandom.current().nextDouble();
    for (int i = 0; i < proportions.length; ++i) {
      if (selector < proportions[i]) return i;
      selector -= proportions[i];
    }
    return proportions.length;
  }

  @SafeVarargs
  private static MapAction choose(Supplier<MapAction>... choices) {
    return choices[ThreadLocalRandom.current().nextInt(choices.length)].get();
  }
}
