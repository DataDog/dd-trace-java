package com.datadog.iast.taint;

import com.datadog.iast.IastSystem;
import com.datadog.iast.util.Wrapper;
import datadog.trace.api.Config;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.util.AgentTaskScheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Map optimized for taint tracking.
 *
 * <p>This implementation is optimized for low concurrency scenarios, but never fails with high
 * concurrency.
 *
 * <p>This is intentionally a smaller API compared to {@link java.util.Map}. It is a hardcoded
 * interface for {@link TaintedObject} entries, which can be used themselves directly as hash table
 * entries and weak references.
 *
 * <p>This implementation is subject to the following characteristics:
 *
 * <ol>
 *   <li>Keys MUST be compared with identity.
 *   <li>Entries SHOULD be removed when key objects are garbage-collected.
 *   <li>All operations MUST NOT throw, even with concurrent access and modification.
 *   <li>Put operations MAY be lost under concurrent modification.
 *   <li>Entries MUST NOT stay in the map forever
 * </ol>
 *
 * <p><i>Capacity</i> is fixed, so there is no rehashing.
 *
 * <p>This implementation works reasonably well under high concurrency, but it will lose some writes
 * in that case.
 */
public interface TaintedMap extends Iterable<TaintedObject> {

  /** Default capacity. It MUST be a power of 2. */
  int DEFAULT_CAPACITY = 1 << 14;

  /** Bitmask to convert hashes to positive integers. */
  int POSITIVE_MASK = Integer.MAX_VALUE;

  /** Max allowed size for the linked list inside a bucket */
  int DEFAULT_MAX_BUCKET_SIZE = 10;

  /** Max age of entries contained in the map (worst case will be {@code 2 * maxAge}) */
  int DEFAULT_MAX_AGE = 5;

  TimeUnit DEFAULT_MAX_AGE_UNIT = TimeUnit.MINUTES;

  /**
   * Builds an instance suitable to be used while in short-lived contexts (e.g. a request), in that
   * cases no purge will happen as they will be cleared on the end of the context.
   */
  static TaintedMap build(final int capacity) {
    final TaintedMapImpl map =
        new TaintedMapImpl(capacity, DEFAULT_MAX_BUCKET_SIZE, -1, null, null);
    return IastSystem.DEBUG ? new Debug(map) : map;
  }

  /**
   * Builds an instance suitable to be used in long lived-context (e.g a global instance), in that
   * case there is a purge logic that will clear stale entries according to the scheduled interval.
   */
  static TaintedMap buildWithPurge(final int capacity, int maxAge, TimeUnit maxAgeUnit) {
    final TaintedMapImpl map =
        new TaintedMapImpl(
            capacity, DEFAULT_MAX_BUCKET_SIZE, maxAge, maxAgeUnit, AgentTaskScheduler.get());
    return IastSystem.DEBUG ? new Debug(map) : map;
  }

  @Nullable
  TaintedObject get(@Nonnull Object key);

  void put(final @Nonnull TaintedObject entry);

  int count();

  void clear();

  class TaintedMapImpl implements TaintedMap, Runnable {

    protected final TaintedObject[] table;

    /** Bitmask for fast modulo with table length. */
    protected final int lengthMask;

    /** Max size of each bucket. */
    protected final int maxBucketSize;

    /**
     * Flag for the current alive tainted objects (red/black style marking for max age calculation).
     */
    @SuppressFBWarnings(value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE", justification = "TODO")
    protected boolean generation;

    /** Whether to collect the {@link IastMetric#TAINTED_FLAT_MODE} metric or not */
    protected boolean collectFlatBucketMetric;

    /** Default constructor. Uses {@link #DEFAULT_CAPACITY}. */
    TaintedMapImpl() {
      this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a new hash map with the given capacity, a max bucket size of {@link
     * #DEFAULT_MAX_BUCKET_SIZE} and a max age of {@link #DEFAULT_MAX_AGE} with {@link
     * #DEFAULT_MAX_AGE_UNIT} units
     */
    TaintedMapImpl(int capacity) {
      this(capacity, DEFAULT_MAX_BUCKET_SIZE, DEFAULT_MAX_AGE, DEFAULT_MAX_AGE_UNIT);
    }

    /**
     * Create a new hash map with the given capacity and purge schedule.
     *
     * @param capacity Capacity of the internal array. It must be a power of 2.
     * @param maxBucketSize Max size for each bucket
     * @param maxAge max time an entry can stay in the map (can take up to {@code 2 * maxAge} in the
     *     worst case)
     * @param maxAgeUnit unit for the max age
     */
    TaintedMapImpl(
        final int capacity, final int maxBucketSize, final int maxAge, final TimeUnit maxAgeUnit) {
      this(capacity, maxBucketSize, maxAge, maxAgeUnit, AgentTaskScheduler.get());
    }

    TaintedMapImpl(
        final int capacity,
        final int maxBucketSize,
        final int maxAge,
        @Nullable final TimeUnit maxAgeUnit,
        @Nullable final AgentTaskScheduler scheduler) {
      table = new TaintedObject[capacity];
      lengthMask = table.length - 1;
      generation = true;
      this.maxBucketSize = maxBucketSize;
      final Verbosity verbosity = Config.get().getIastTelemetryVerbosity();
      collectFlatBucketMetric = IastMetric.TAINTED_FLAT_MODE.isEnabled(verbosity);
      generation = true;
      if (scheduler != null) {
        scheduler.weakScheduleAtFixedRate(this, maxAge, maxAge, maxAgeUnit);
      }
    }

    /**
     * Returns the {@link TaintedObject} for the given input object.
     *
     * @param key Key object.
     * @return The {@link TaintedObject} if it exists, {@code null} otherwise.
     */
    @Nullable
    @Override
    public TaintedObject get(final @Nonnull Object key) {
      final int index = indexObject(key);
      TaintedObject entry = head(index);
      while (entry != null) {
        if (key == entry.get()) {
          return entry;
        }
        entry = next(entry);
      }
      return null;
    }

    /**
     * Put a new {@link TaintedObject} in the hash table, always to the tail of the chain. It will
     * not insert the element if it is already present in the map. This method will lose puts in
     * concurrent scenarios.
     *
     * @param entry Tainted object.
     */
    @Override
    public void put(final @Nonnull TaintedObject entry) {
      final int index = index(entry.positiveHashCode);
      TaintedObject cur = head(index);
      if (cur == null) {
        table[index] = entry;
        entry.generation = generation;
      } else {
        int bucketSize = 1;
        TaintedObject next;
        while ((next = next(cur)) != null) {
          if (cur.positiveHashCode == entry.positiveHashCode && cur.get() == entry.get()) {
            // Duplicate, exit early.
            return;
          }
          bucketSize++;
          cur = next;
        }
        if (bucketSize >= maxBucketSize) {
          table[index] = entry;
          if (collectFlatBucketMetric) {
            IastMetricCollector.add(IastMetric.TAINTED_FLAT_MODE, 1);
          }
        } else {
          cur.next = entry;
        }
        entry.generation = generation;
      }
    }

    @Override
    public void clear() {
      Arrays.fill(table, null);
    }

    @Nonnull
    @Override
    public Iterator<TaintedObject> iterator() {
      return iterator(0, table.length);
    }

    @Override
    public int count() {
      int size = 0;
      for (int i = 0; i < table.length; i++) {
        TaintedObject entry = table[i];
        while (entry != null) {
          entry = entry.next;
          size++;
        }
      }
      return size;
    }

    private Iterator<TaintedObject> iterator(final int start, final int stop) {
      return new Iterator<TaintedObject>() {
        int currentIndex = start;
        @Nullable TaintedObject currentSubPos;

        @Override
        public boolean hasNext() {
          if (currentSubPos != null) {
            return true;
          }
          for (; currentIndex < stop; currentIndex++) {
            if (table[currentIndex] != null) {
              return true;
            }
          }
          return false;
        }

        @Override
        public TaintedObject next() {
          if (currentSubPos != null) {
            TaintedObject toReturn = currentSubPos;
            currentSubPos = toReturn.next;
            return toReturn;
          }
          for (; currentIndex < stop; currentIndex++) {
            final TaintedObject entry = table[currentIndex];
            if (entry != null) {
              currentSubPos = entry.next;
              currentIndex++;
              return entry;
            }
          }
          throw new NoSuchElementException();
        }
      };
    }

    protected int indexObject(final Object obj) {
      return index(positiveHashCode(System.identityHashCode(obj)));
    }

    protected int positiveHashCode(final int h) {
      return h & POSITIVE_MASK;
    }

    protected int index(int h) {
      return h & lengthMask;
    }

    @Nullable
    protected TaintedObject head(final int index) {
      final TaintedObject head = findAlive(table[index]);
      table[index] = head;
      return head;
    }

    @Nullable
    protected TaintedObject next(@Nonnull final TaintedObject item) {
      final TaintedObject next = findAlive(item.next);
      item.next = next;
      return next;
    }

    /** Gets the first reachable reference that has not been GC'ed */
    @Nullable
    protected TaintedObject findAlive(@Nullable TaintedObject item) {
      while (item != null && item.get() == null) {
        item = item.next;
      }
      return item;
    }

    /** Runnable used to purge stale entries after max age */
    @Override
    public void run() {
      for (int bucket = 0; bucket < table.length; bucket++) {
        for (TaintedObject cur = head(bucket), prev = null; cur != null; cur = next(cur)) {
          if (cur.generation != generation) { // entry added to the map in previous generation
            if (prev == null) {
              table[bucket] = cur.next;
            } else {
              prev.next = cur.next;
              prev = cur;
            }
          } else {
            prev = cur;
          }
        }
      }
      generation = !generation;
    }
  }

  class Debug implements TaintedMap, Wrapper<TaintedMapImpl> {

    static final Logger LOGGER = LoggerFactory.getLogger(TaintedMap.class);

    /** Interval to compute statistics in debug mode * */
    static final int COMPUTE_STATISTICS_INTERVAL = 1 << 17;

    private final TaintedMapImpl delegate;

    private final AtomicLong puts = new AtomicLong(0);

    public Debug(final TaintedMapImpl delegate) {
      this.delegate = delegate;
    }

    @Override
    public void put(@Nonnull final TaintedObject entry) {
      delegate.put(entry);
      final long putOps = puts.updateAndGet(current -> current == Long.MAX_VALUE ? 0 : current + 1);
      if (putOps % COMPUTE_STATISTICS_INTERVAL == 0 && LOGGER.isDebugEnabled()) {
        AgentTaskScheduler.get().execute(this::computeStatistics);
      }
    }

    @Nullable
    @Override
    public TaintedObject get(@Nonnull final Object key) {
      return delegate.get(key);
    }

    @Override
    public int count() {
      return delegate.count();
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Nonnull
    @Override
    public Iterator<TaintedObject> iterator() {
      return delegate.iterator();
    }

    @Override
    public TaintedMapImpl unwrap() {
      return delegate;
    }

    protected void computeStatistics() {
      final TaintedObject[] table = delegate.table;
      final int[] chains = new int[table.length];
      long stale = 0;
      long count = 0;
      for (int bucket = 0; bucket < table.length; bucket++) {
        TaintedObject cur = table[bucket];
        int chainLength = 0;
        while (cur != null) {
          count++;
          chainLength++;
          if (cur.get() == null) {
            stale++;
          }
          cur = cur.next;
        }
        chains[bucket] = chainLength;
      }
      Arrays.sort(chains);
      LOGGER.debug(
          "Map [size:{}, count:{}, stale:{}], Chains [{}, {}, {}, {}, {}, {}]",
          delegate.table.length,
          count,
          percentage(stale, count),
          average(chains),
          percentile(chains, 50),
          percentile(chains, 75),
          percentile(chains, 90),
          percentile(chains, 99),
          percentile(chains, 100));
    }

    private static String percentage(final long actual, final long total) {
      return String.format("%2.2f%%", total == 0 ? 0 : (actual * 100D / total));
    }

    /** Computes different percentiles, values array MUST be sorted beforehand */
    private static String percentile(final int[] values, final int percentile) {
      assert percentile >= 0 && percentile <= 100;
      final String prefix;
      final int value;
      switch (percentile) {
        case 0:
          prefix = "min";
          value = values[0];
          break;
        case 100:
          prefix = "max";
          value = values[values.length - 1];
          break;
        default:
          prefix = "pct" + percentile;
          value = values[Math.round(values.length * percentile / 100F)];
          break;
      }
      return String.format("%s:%s", prefix, value);
    }

    private static String average(final int[] values) {
      return String.format("avg:%2.2f", Arrays.stream(values).sum() / (double) values.length);
    }
  }

  class NoOp implements TaintedMap {

    public static final TaintedMap INSTANCE = new NoOp();

    @Nullable
    @Override
    public TaintedObject get(@Nonnull Object key) {
      return null;
    }

    @Override
    public void put(@Nonnull TaintedObject entry) {}

    @Override
    public int count() {
      return 0;
    }

    @Override
    public void clear() {}

    @Nonnull
    @Override
    public Iterator<TaintedObject> iterator() {
      return Collections.emptyIterator();
    }
  }
}
