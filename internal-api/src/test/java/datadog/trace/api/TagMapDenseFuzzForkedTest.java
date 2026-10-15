package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.TagMapFuzzTest.MapAction;
import datadog.trace.api.TagMapFuzzTest.TestCase;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes TagMap with the dense store enabled, replaying random action sequences against a {@code
 * HashMap} oracle through {@link TagMapFuzzTest}'s machinery (which also runs {@code
 * checkIntegrity} after each step).
 *
 * <p>Keys come from three pools: slotted known tags (dense), known tags without a slot, and custom
 * tags (both bucketed). Known tags are drawn by their canonical Datadog name, excluding names
 * shared by a tag per direction, so the oracle's keys match TagMap's. The slotted pool is larger
 * than the dense arrays' initial capacity, so growth is exercised, and includes tags that share a
 * slot. Forked because the flag is read once per JVM.
 */
class TagMapDenseFuzzForkedTest {
  static {
    System.setProperty("dd.trace.experimental.dense.tags.enabled", "true");
  }

  static final int SINGLE_MAP_CASES = 1500;
  static final int MERGE_CASES = 400;
  static final int MAX_ACTIONS = 40;
  static final int MIN_ACTIONS = 8;
  static final int CUSTOM_SPACE = 48;

  static final List<String> SLOTTED = new ArrayList<>();
  static final List<String> UNSLOTTED = new ArrayList<>();

  enum Regime {
    DENSE_ONLY,
    BUCKET_ONLY,
    MIXED
  }

  @BeforeAll
  static void collectKeys() throws IllegalAccessException {
    assertTrue(Config.get().isTraceDenseTagsEnabled(), "dense store must be on in this JVM");
    Set<String> slotted = new LinkedHashSet<>();
    Set<String> unslotted = new LinkedHashSet<>();
    for (Field field : KnownTags.class.getFields()) {
      if (field.getType() != long.class) continue;
      long id = field.getLong(null);
      String name = KnownTagCodec.nameOf(id);
      if (name == null || KnownTagCodec.keyOf(name) != id) continue; // shared per-direction name
      (KnownTagCodec.hasSlot(id) ? slotted : unslotted).add(name);
    }
    SLOTTED.addAll(slotted);
    UNSLOTTED.addAll(unslotted);
    assertTrue(SLOTTED.size() > 12, "slotted pool should outgrow the dense arrays' initial size");
    assertTrue(!UNSLOTTED.isEmpty(), "need known tags without a slot");
  }

  @Test
  void denseOnlyFuzz() {
    runRegime(Regime.DENSE_ONLY);
  }

  @Test
  void bucketOnlyFuzz() {
    runRegime(Regime.BUCKET_ONLY);
  }

  @Test
  void mixedFuzz() {
    runRegime(Regime.MIXED);
  }

  private static void runRegime(Regime regime) {
    for (int i = 0; i < SINGLE_MAP_CASES; ++i) {
      TagMapFuzzTest.test(TagMapType.DENSE, generateTest(regime));
    }
    for (int i = 0; i < MERGE_CASES; ++i) {
      TagMap mapA = TagMapFuzzTest.test(TagMapType.DENSE, generateTest(regime));
      TagMap mapB = TagMapFuzzTest.test(TagMapType.DENSE, generateTest(regime));

      HashMap<String, Object> hashA = new HashMap<>(mapA);
      HashMap<String, Object> hashB = new HashMap<>(mapB);

      mapA.putAll(mapB);
      hashA.putAll(hashB);

      TagMapFuzzTest.assertMapEquals(hashA, mapA);
    }
  }

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
    boolean dense;
    switch (regime) {
      case DENSE_ONLY:
        dense = true;
        break;
      case BUCKET_ONLY:
        dense = false;
        break;
      default:
        dense = r.nextBoolean();
    }
    if (dense) {
      // every known tag is dense, slotted or not
      return r.nextInt(4) == 0
          ? UNSLOTTED.get(r.nextInt(UNSLOTTED.size()))
          : SLOTTED.get(r.nextInt(SLOTTED.size()));
    }
    return "custom-" + r.nextInt(CUSTOM_SPACE);
  }

  private static String randomValue() {
    return "values-" + ThreadLocalRandom.current().nextInt();
  }

  private static String[] randomKeysAndValues(Regime regime) {
    int numEntries = ThreadLocalRandom.current().nextInt(SLOTTED.size() + CUSTOM_SPACE);
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
