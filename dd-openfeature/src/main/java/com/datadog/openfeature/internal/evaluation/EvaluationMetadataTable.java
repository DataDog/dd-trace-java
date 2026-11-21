package com.datadog.openfeature.internal.evaluation;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Split;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * Shares the flag metadata of successful evaluations that resolve the same split in the same time
 * bucket. With a bounded bucket, this is a burst coalescer, not a cache: an entry is only reusable
 * within the bucket it was built in, because the metadata carries the evaluation timestamp of the
 * evaluation that built it. A later bucket builds and publishes a new entry. A bucket of {@code W}
 * milliseconds reports the evaluations that reuse an entry up to {@code W - 1} milliseconds early:
 * the default of 10 milliseconds, at most 9 milliseconds early in the 10 second flush interval of
 * the flag evaluation events, shares most of what wider buckets share. A bucket of one millisecond,
 * the resolution of the timestamp, keeps the timestamp exact. The {@link #UNBOUNDED} bucket leaves
 * the timestamp out of the metadata, so the hooks read their own clock and the entry of a split is
 * reusable until the slot is replaced.
 *
 * <p>The table is a fixed, direct-mapped array of slots. Each slot holds the metadata built last
 * for one key: the {@link Split}, {@link Allocation} and {@link Flag} references, the configuration
 * consent to observe full evaluation data, the exposure state, and the time bucket. Everything else
 * in the metadata is fixed by the split, its allocation and its flag. The key compares references,
 * so a reloaded configuration, whose objects are new, never matches an entry of the previous one,
 * and no metadata field can be left out of the key.
 *
 * <p>The table takes no lock. An entry has only final fields and is published with a single
 * reference store, so a reader sees either a whole entry or none. Two evaluations that miss the
 * same slot at once both build metadata and the last store wins: a lost race only costs a rebuild.
 */
final class EvaluationMetadataTable {
  /** The default number of slots. */
  static final int DEFAULT_SIZE = 1024;

  /** The largest number of slots. */
  static final int MAX_SIZE = 1 << 16;

  /** The default time bucket width. */
  static final int DEFAULT_TIME_BUCKET_MS = 10;

  /**
   * The widest time bucket: the flush interval of the flag evaluation events, which aggregate the
   * first and last evaluation times of each interval.
   */
  static final int MAX_TIME_BUCKET_MS = 10_000;

  /** The time bucket width that leaves the evaluation timestamp out of the metadata. */
  static final int UNBOUNDED = 0;

  /** The evaluation logs no exposure, so the metadata carries no exposure state. */
  static final int NO_EXPOSURE = 0;

  /** The evaluation logs an exposure that was not recorded yet. */
  static final int NEW_EXPOSURE = 1;

  /** The evaluation logs an exposure that was already recorded. */
  static final int RECORDED_EXPOSURE = 2;

  private final Entry[] slots;
  private final int mask;
  @Nullable private final LongAdder misses;

  private EvaluationMetadataTable(final int size, @Nullable final LongAdder misses) {
    this.slots = new Entry[size];
    this.mask = size - 1;
    this.misses = misses;
  }

  /**
   * Creates a table.
   *
   * @param requestedSize the requested number of slots: rounded up to a power of two and capped at
   *     {@link #MAX_SIZE}, or 0 to share no metadata.
   * @return the table, or {@code null} if the requested size is 0 or negative.
   */
  @Nullable
  static EvaluationMetadataTable create(final int requestedSize) {
    return create(requestedSize, null);
  }

  /**
   * Creates a table that counts its misses, for the benchmarks.
   *
   * @param requestedSize the requested number of slots, as for {@link #create(int)}.
   * @param misses the counter incremented on each lookup that finds no entry, or {@code null}.
   * @return the table, or {@code null} if the requested size is 0 or negative.
   */
  @Nullable
  static EvaluationMetadataTable create(final int requestedSize, @Nullable final LongAdder misses) {
    final int size = size(requestedSize);
    return size == 0 ? null : new EvaluationMetadataTable(size, misses);
  }

  /**
   * @param requestedSize the requested number of slots.
   * @return the number of slots of a table of this requested size, 0 when it shares no metadata.
   */
  static int size(final int requestedSize) {
    if (requestedSize <= 0) {
      return 0;
    }
    if (requestedSize >= MAX_SIZE) {
      return MAX_SIZE;
    }
    return requestedSize == 1 ? 1 : Integer.highestOneBit(requestedSize - 1) << 1;
  }

  /**
   * @param requestedMs the requested time bucket width, in milliseconds.
   * @return the width used: {@link #UNBOUNDED} for 0, at least 1 and at most {@link
   *     #MAX_TIME_BUCKET_MS} otherwise.
   */
  static int timeBucketMs(final int requestedMs) {
    if (requestedMs == UNBOUNDED) {
      return UNBOUNDED;
    }
    return Math.max(1, Math.min(requestedMs, MAX_TIME_BUCKET_MS));
  }

  /** The number of slots. */
  int size() {
    return slots.length;
  }

  /**
   * Finds the metadata built for this key.
   *
   * @return the metadata, or {@code null} if the slot holds another key.
   */
  @Nullable
  ImmutableMetadata get(
      final Flag flag,
      final Allocation allocation,
      final Split split,
      final boolean observeFullEvaluationData,
      final int exposure,
      final long timeBucket) {
    final Entry entry = slots[index(split, exposure)];
    if (entry != null
        && entry.split == split
        && entry.timeBucket == timeBucket
        && entry.exposure == exposure
        && entry.observeFullEvaluationData == observeFullEvaluationData
        && entry.allocation == allocation
        && entry.flag == flag) {
      return entry.metadata;
    }
    if (misses != null) {
      misses.increment();
    }
    return null;
  }

  /** Publishes the metadata built for this key, replacing the slot's entry. */
  void put(
      final Flag flag,
      final Allocation allocation,
      final Split split,
      final boolean observeFullEvaluationData,
      final int exposure,
      final long timeBucket,
      final ImmutableMetadata metadata) {
    slots[index(split, exposure)] =
        new Entry(
            flag, allocation, split, observeFullEvaluationData, exposure, timeBucket, metadata);
  }

  private int index(final Split split, final int exposure) {
    // The exposure states of a split take neighboring slots, so a subject that was already exposed
    // does not evict the entry of one that was not. The time bucket is left out: the next one
    // replaces the entry of the previous one instead of evicting another split.
    int hash = (System.identityHashCode(split) << 2 | exposure) * 0x9E3779B9;
    hash ^= hash >>> 16;
    return hash & mask;
  }

  /** One immutable slot entry. Its final fields make it safe to publish without a barrier. */
  private static final class Entry {
    final Flag flag;
    final Allocation allocation;
    final Split split;
    final boolean observeFullEvaluationData;
    final int exposure;
    final long timeBucket;
    final ImmutableMetadata metadata;

    Entry(
        final Flag flag,
        final Allocation allocation,
        final Split split,
        final boolean observeFullEvaluationData,
        final int exposure,
        final long timeBucket,
        final ImmutableMetadata metadata) {
      this.flag = flag;
      this.allocation = allocation;
      this.split = split;
      this.observeFullEvaluationData = observeFullEvaluationData;
      this.exposure = exposure;
      this.timeBucket = timeBucket;
      this.metadata = metadata;
    }
  }
}
