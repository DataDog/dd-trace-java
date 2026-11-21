package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.evaluation.EvaluationMetadataTable.NEW_EXPOSURE;
import static com.datadog.openfeature.internal.evaluation.EvaluationMetadataTable.RECORDED_EXPOSURE;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVAL_TIMESTAMP_MS;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.OBSERVE_FULL_EVALUATION_DATA;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.ExperimentationOptOutMode;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class EvaluationMetadataTableTest {
  private static final ImmutableMetadata METADATA = ImmutableMetadata.builder().build();

  @TableTest({
    "scenario     | requested | size ",
    "disabled     | 0         | 0    ",
    "negative     | -1        | 0    ",
    "one          | 1         | 1    ",
    "rounded up   | 3         | 4    ",
    "power of two | 1024      | 1024 ",
    "next power   | 1025      | 2048 ",
    "capped       | 100000    | 65536"
  })
  void roundsTheSizeUpToAPowerOfTwo(final int requested, final int size) {
    assertEquals(size, EvaluationMetadataTable.size(requested));
    final EvaluationMetadataTable table = EvaluationMetadataTable.create(requested);
    if (size == 0) {
      assertNull(table);
    } else {
      assertEquals(size, table.size());
    }
  }

  @Test
  void findsTheMetadataOfTheSameKeyInTheSameMillisecondOnly() {
    final EvaluationMetadataTable table = EvaluationMetadataTable.create(16);
    final Split split = split();
    final Allocation allocation = allocation("allocation", split, true);
    final Flag flag = flag("flag", allocation);
    table.put(flag, allocation, split, true, NEW_EXPOSURE, 100L, METADATA);

    assertSame(METADATA, table.get(flag, allocation, split, true, NEW_EXPOSURE, 100L));
    assertNull(table.get(flag, allocation, split, true, NEW_EXPOSURE, 101L));
    assertNull(table.get(flag, allocation, split, false, NEW_EXPOSURE, 100L));
    assertNull(table.get(flag, allocation, split, true, RECORDED_EXPOSURE, 100L));
    assertNull(table.get(flag, allocation, split(), true, NEW_EXPOSURE, 100L));
    final Allocation other = allocation("allocation", split, true);
    assertNull(table.get(flag, other, split, true, NEW_EXPOSURE, 100L));
    assertNull(table.get(flag("flag", allocation), allocation, split, true, NEW_EXPOSURE, 100L));
  }

  @Test
  void evaluationsInTheSameMillisecondShareTheMetadata() {
    final FlagEvaluator evaluator = evaluator(EvaluationMetadataTable.DEFAULT_SIZE);
    final ServerConfiguration config = config(true, flag("flag", allocation("a", split(), true)));
    int shared = 0;
    for (int i = 0; i < 1000 && shared < 10; i++) {
      final ImmutableMetadata first = evaluate(evaluator, config, "flag", "user");
      final ImmutableMetadata second = evaluate(evaluator, config, "flag", "user");
      if (first.getLong(EVAL_TIMESTAMP_MS).equals(second.getLong(EVAL_TIMESTAMP_MS))) {
        assertSame(first, second);
        shared++;
      } else {
        assertNotSame(first, second);
      }
    }
    assertEquals(10, shared);
  }

  @Test
  void aLaterMillisecondBuildsNewMetadata() {
    final FlagEvaluator evaluator = evaluator(EvaluationMetadataTable.DEFAULT_SIZE);
    final ServerConfiguration config = config(true, flag("flag", allocation("a", split(), true)));
    final ImmutableMetadata first = evaluate(evaluator, config, "flag", "user");
    final long firstMs = first.getLong(EVAL_TIMESTAMP_MS);
    ImmutableMetadata later;
    do {
      later = evaluate(evaluator, config, "flag", "user");
    } while (later.getLong(EVAL_TIMESTAMP_MS) == firstMs);

    assertNotSame(first, later);
    assertTrue(later.getLong(EVAL_TIMESTAMP_MS) > firstMs);
    final Map<String, Object> expected = new HashMap<>(first.asUnmodifiableMap());
    expected.put(EVAL_TIMESTAMP_MS, later.getLong(EVAL_TIMESTAMP_MS));
    assertEquals(expected, later.asUnmodifiableMap());
  }

  @Test
  void keysOnTheConsentOfTheConfiguration() {
    final FlagEvaluator evaluator = evaluator(EvaluationMetadataTable.DEFAULT_SIZE);
    final Flag flag = flag("flag", allocation("a", split(), true));
    final ServerConfiguration consenting = config(true, flag);
    final ServerConfiguration withheld = config(false, flag);

    for (int i = 0; i < 100; i++) {
      assertEquals(
          true,
          evaluate(evaluator, consenting, "flag", "user").getBoolean(OBSERVE_FULL_EVALUATION_DATA));
      assertEquals(
          false,
          evaluate(evaluator, withheld, "flag", "user").getBoolean(OBSERVE_FULL_EVALUATION_DATA));
    }
  }

  @Test
  void keysOnTheExposureState() {
    final FlagEvaluator evaluator = evaluator(EvaluationMetadataTable.DEFAULT_SIZE);
    final ServerConfiguration config = config(true, flag("flag", allocation("a", split(), true)));

    for (int i = 0; i < 100; i++) {
      assertEquals(
          true, evaluate(evaluator, config, "flag", "exposed").getBoolean(EXPOSURE_CACHE_HIT));
      assertEquals(
          false, evaluate(evaluator, config, "flag", "user").getBoolean(EXPOSURE_CACHE_HIT));
    }
  }

  @Test
  void sharesAcrossExposureStatesWhenExposuresAreDisabled() {
    final FlagEvaluator evaluator =
        new FlagEvaluator(
            false,
            exposedUser(),
            ExperimentationOptOutMode.DISABLED,
            false,
            EvaluationMetadataTable.DEFAULT_SIZE,
            EvaluationMetadataTable.UNBOUNDED);
    final ServerConfiguration config = config(true, flag("flag", allocation("a", split(), true)));

    final ImmutableMetadata exposed = evaluate(evaluator, config, "flag", "exposed");
    assertNull(exposed.getBoolean(DO_LOG));
    assertNull(exposed.getBoolean(EXPOSURE_CACHE_HIT));
    assertSame(exposed, evaluate(evaluator, config, "flag", "user"));
  }

  @Test
  void keysOnTheAllocationAndItsDoLog() {
    final FlagEvaluator evaluator = evaluator(EvaluationMetadataTable.DEFAULT_SIZE);
    // Directly built configurations can share a split between allocations.
    final Split split = split();
    final ServerConfiguration logging = config(true, flag("flag", allocation("a", split, true)));
    final ServerConfiguration silent = config(true, flag("flag", allocation("b", split, false)));

    for (int i = 0; i < 100; i++) {
      final ImmutableMetadata logged = evaluate(evaluator, logging, "flag", "user");
      final ImmutableMetadata unlogged = evaluate(evaluator, silent, "flag", "user");
      assertEquals("a", logged.getString(ALLOCATION_KEY));
      assertEquals(true, logged.getBoolean(DO_LOG));
      assertEquals("b", unlogged.getString(ALLOCATION_KEY));
      assertNull(unlogged.getBoolean(DO_LOG));
      assertNull(unlogged.getBoolean(EXPOSURE_CACHE_HIT));
    }
  }

  @Test
  void aReloadedConfigurationBuildsNewMetadata() {
    final FlagEvaluator evaluator = evaluator(EvaluationMetadataTable.DEFAULT_SIZE);
    final ServerConfiguration before = config(true, flag("flag", allocation("a", split(), true)));
    final ServerConfiguration after = config(true, flag("flag", allocation("b", split(), true)));

    for (int i = 0; i < 100; i++) {
      final ImmutableMetadata old = evaluate(evaluator, before, "flag", "user");
      final ImmutableMetadata reloaded = evaluate(evaluator, after, "flag", "user");
      assertNotSame(old, reloaded);
      assertEquals("a", old.getString(ALLOCATION_KEY));
      assertEquals("b", reloaded.getString(ALLOCATION_KEY));
    }
  }

  @Test
  void aZeroSizeBuildsTheMetadataOfEachEvaluation() {
    final FlagEvaluator evaluator = evaluator(0);
    final ServerConfiguration config = config(true, flag("flag", allocation("a", split(), true)));
    for (int i = 0; i < 100; i++) {
      final ImmutableMetadata first = evaluate(evaluator, config, "flag", "user");
      final ImmutableMetadata second = evaluate(evaluator, config, "flag", "user");
      assertNotSame(first, second);
      if (first.getLong(EVAL_TIMESTAMP_MS).equals(second.getLong(EVAL_TIMESTAMP_MS))) {
        assertEquals(first.asUnmodifiableMap(), second.asUnmodifiableMap());
      }
    }
  }

  @TableTest({
    "scenario    | requested | width",
    "unbounded   | 0         | 0    ",
    "negative    | -5        | 1    ",
    "millisecond | 1         | 1    ",
    "wider       | 100       | 100  ",
    "capped      | 60000     | 10000"
  })
  void capsTheTimeBucketWidth(final int requested, final int width) {
    assertEquals(width, EvaluationMetadataTable.timeBucketMs(requested));
  }

  @Test
  void evaluationsInTheSameBucketShareTheMetadataOfTheFirstOne() {
    final int width = 10;
    final FlagEvaluator evaluator = evaluator(EvaluationMetadataTable.DEFAULT_SIZE, width);
    final ServerConfiguration config = config(true, flag("flag", allocation("a", split(), true)));
    // Start at the beginning of a bucket, so its later milliseconds follow.
    while (System.currentTimeMillis() % width != 0) {
      Thread.onSpinWait();
    }
    final ImmutableMetadata first = evaluate(evaluator, config, "flag", "user");
    final long firstMs = first.getLong(EVAL_TIMESTAMP_MS);
    int laterMilliseconds = 0;
    long before;
    ImmutableMetadata later;
    while (true) {
      before = System.currentTimeMillis();
      later = evaluate(evaluator, config, "flag", "user");
      if (later.getLong(EVAL_TIMESTAMP_MS) / width != firstMs / width) {
        break;
      }
      // A later millisecond of the bucket still reports the time of the first evaluation.
      assertSame(first, later);
      if (before > firstMs) {
        laterMilliseconds++;
      }
    }

    assertTrue(laterMilliseconds > 0);
    assertNotSame(first, later);
    assertTrue(later.getLong(EVAL_TIMESTAMP_MS) >= before);
  }

  @Test
  void anUnboundedBucketLeavesTheTimestampOutAndKeepsTheMetadata() {
    final FlagEvaluator evaluator =
        evaluator(EvaluationMetadataTable.DEFAULT_SIZE, EvaluationMetadataTable.UNBOUNDED);
    final Flag flag = flag("flag", allocation("a", split(), true));
    final ImmutableMetadata first = evaluate(evaluator, config(true, flag), "flag", "user");
    final long start = System.currentTimeMillis();

    assertNull(first.getLong(EVAL_TIMESTAMP_MS));
    assertEquals("a", first.getString(ALLOCATION_KEY));
    while (System.currentTimeMillis() < start + 3) {
      assertSame(first, evaluate(evaluator, config(true, flag), "flag", "user"));
    }
    assertNotSame(first, evaluate(evaluator, config(false, flag), "flag", "user"));
  }

  @Test
  void anUnboundedBucketWithoutTableBuildsTimestamplessMetadata() {
    final FlagEvaluator evaluator = evaluator(0, EvaluationMetadataTable.UNBOUNDED);
    final ServerConfiguration config = config(true, flag("flag", allocation("a", split(), true)));
    final ImmutableMetadata first = evaluate(evaluator, config, "flag", "user");
    final ImmutableMetadata second = evaluate(evaluator, config, "flag", "user");

    assertNotSame(first, second);
    assertNull(first.getLong(EVAL_TIMESTAMP_MS));
    assertEquals(first.asUnmodifiableMap(), second.asUnmodifiableMap());
  }

  @TableTest({
    "scenario     | timeBucketMs",
    "millisecond  | 1           ",
    "wider bucket | 10          ",
    "unbounded    | 0           "
  })
  void neverReturnsTheMetadataOfAnotherKeyUnderContention(final int timeBucketMs) throws Exception {
    // A single slot makes every key collide.
    final FlagEvaluator evaluator =
        new FlagEvaluator(
            false,
            exposedUser(),
            ExperimentationOptOutMode.DISABLED,
            true,
            EvaluationMetadataTable.create(1),
            timeBucketMs);
    final List<Flag> flags = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      flags.add(flag("flag-" + i, allocation("allocation-" + i, split(), i % 2 == 0)));
    }
    final ServerConfiguration consenting = config(true, flags.toArray(new Flag[0]));
    final ServerConfiguration withheld = config(false, flags.toArray(new Flag[0]));
    final int threads = 8;
    final CountDownLatch start = new CountDownLatch(1);
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      final List<Future<?>> results = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        results.add(
            executor.submit(() -> {
              start.await();
              final ThreadLocalRandom random = ThreadLocalRandom.current();
              for (int i = 0; i < 50_000; i++) {
                final int flag = random.nextInt(flags.size());
                final boolean consent = random.nextBoolean();
                final String subject = random.nextBoolean() ? "exposed" : "user";
                final long before = System.currentTimeMillis();
                final ImmutableMetadata metadata =
                    evaluate(evaluator, consent ? consenting : withheld, "flag-" + flag, subject);
                final long after = System.currentTimeMillis();
                assertEquals("flag-" + flag, metadata.getString("flagKey"));
                assertEquals("allocation-" + flag, metadata.getString(ALLOCATION_KEY));
                assertEquals(consent, metadata.getBoolean(OBSERVE_FULL_EVALUATION_DATA));
                assertEquals(
                    flag % 2 == 0 ? "exposed".equals(subject) : null,
                    metadata.getBoolean(EXPOSURE_CACHE_HIT));
                if (timeBucketMs == EvaluationMetadataTable.UNBOUNDED) {
                  assertNull(metadata.getLong(EVAL_TIMESTAMP_MS));
                } else {
                  // A reused entry reports the time of the evaluation that built it, in the same
                  // bucket.
                  final long ms = metadata.getLong(EVAL_TIMESTAMP_MS);
                  final long earliest = before - before % timeBucketMs;
                  assertTrue(
                      earliest <= ms && ms <= after, ms + " not in " + earliest + ".." + after);
                }
              }
              return null;
            }));
      }
      start.countDown();
      for (final Future<?> result : results) {
        result.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /** An evaluator with a one millisecond bucket, which keeps the timestamps exact. */
  private static FlagEvaluator evaluator(final int tableSize) {
    return evaluator(tableSize, 1);
  }

  private static FlagEvaluator evaluator(final int tableSize, final int timeBucketMs) {
    return new FlagEvaluator(
        false, exposedUser(), ExperimentationOptOutMode.DISABLED, true, tableSize, timeBucketMs);
  }

  private static FlagEvaluator.ExposureLookup exposedUser() {
    return (flag, subject, allocation, variant, serialId) -> "exposed".equals(subject);
  }

  private static ImmutableMetadata evaluate(
      final FlagEvaluator evaluator,
      final ServerConfiguration config,
      final String flag,
      final String subject) {
    final ImmutableMetadata metadata =
        evaluator
            .evaluate(config, Integer.class, flag, 0, new MutableContext(subject))
            .getFlagMetadata();
    return metadata;
  }

  private static Split split() {
    return new Split(emptyList(), "on", emptyMap(), 7);
  }

  private static Allocation allocation(final String key, final Split split, final boolean doLog) {
    return new Allocation(key, null, null, null, singletonList(split), doLog);
  }

  private static Flag flag(final String key, final Allocation allocation) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    return new Flag(key, true, ValueType.INTEGER, variations, singletonList(allocation));
  }

  private static ServerConfiguration config(final boolean consent, final Flag... flags) {
    final Map<String, Flag> byKey = new HashMap<>();
    for (final Flag flag : asList(flags)) {
      byKey.put(flag.key, flag);
    }
    return new ServerConfiguration("", "", consent, null, byKey);
  }
}
