package datadog.trace.util;

import datadog.trace.api.function.TriConsumer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A lightweight map, keyed by any type with a stable {@code hashCode}/{@code equals} (typically
 * {@link String}), designed to be small and fast for tiny maps.
 *
 * <p>Supports the common map operations -- {@code set}, {@code get}, {@code remove}, {@code
 * containsKey}, and {@code forEach} -- as an easy, largely footgun-free stand-in wherever a small
 * {@code Map<K, V>} is needed. It deliberately does <em>not</em> implement {@link java.util.Map};
 * the surface is intentionally small.
 *
 * <p>Neither null keys nor null values are supported: {@code set} rejects a null value, so a null
 * {@code get} result unambiguously means "absent". Use {@link #containsKey} if you need to probe
 * for presence separately. Interned/literal keys resolve slightly faster but are not assumed.
 *
 * <p>This class is <em>not</em> thread-safe.
 *
 * <p>Map data is stored in a single flat array that can be embedded into another object via {@link
 * EmbeddingSupport} as a further optimization.
 */
/*
 * Keys are stored in the first half of the array and values in the second half.
 * Key collisions are resolved via linear probing.
 *
 * This layout is intended to optimize scanning for available and matching slots.
 *
 * Key removal is handled by placing a poison key in the previously occupied slot.
 * This approach was chosen so that linear probing can break out of the loop
 * when an empty slot is encountered.
 *
 * Insertions after the a removal can fill the emptied slot.
 * In the event of resizing, removal markers are discarded while assigning
 * slots in the new data array.
 */
public final class LightStringMap<K, V> {
  public static final int DEFAULT_CAPACITY = 8;

  // Slots a fresh (un-tuned) hint seeds -- a reasonable default so a cold site behaves like a
  // plain LightStringMap.createUncapped(); it then self-tunes up or down from here.
  static final int DEFAULT_HINT_SLOTS = DEFAULT_CAPACITY;
  // Floor step-down never drops a hint below (in slots), so a genuinely tiny site can still tune
  // below the default. Must stay >= 1 (a zero-slot table is degenerate).
  static final int MIN_HINT_SLOTS = 1;
  // Step the learned estimate down one power-of-two class every this-many constructions. A
  // power of two so the tick test is a bit-mask. Large => decay is a slow background correction,
  // not something that fights a steady workload.
  static final int DECAY_INTERVAL = 1024;
  // Safety ceiling on how large a hint will pre-provision (in slots). Bounds the shared hint's
  // over-provision from an outlier; a map without a maxCapacity still grows past this on its own.
  static final int MAX_HINT_SLOTS = 1024;

  // Sentinel maxSlots meaning "no hard cap": the map grows freely (numSlots < NO_MAX_SLOTS always
  // holds, so the cap check never fires and set() always stores).
  static final int NO_MAX_SLOTS = Integer.MAX_VALUE;

  private final int initialCapacity;
  // Hard cap on slots (a power of two), or NO_MAX_SLOTS when uncapped. Comes from the sizing hint's
  // maxCapacity so every map at a construction site shares one bound.
  private final int maxSlots;
  @Nullable private final AdaptiveSizingHint sizingHint;
  private Object[] data = EmbeddingSupport.EMPTY_DATA;

  private LightStringMap(int capacity, int maxSlots) {
    this.initialCapacity = capacity;
    this.sizingHint = null;
    this.maxSlots = maxSlots;
  }

  private LightStringMap(@Nonnull AdaptiveSizingHint hint) {
    this.sizingHint = hint;
    this.initialCapacity = hint.seedSlots();
    this.maxSlots = hint.maxSlots();
  }

  /** A new, uncapped map seeded at the default capacity. The "just give me a map" front door. */
  @Nonnull
  public static <K, V> LightStringMap<K, V> createUncapped() {
    return new LightStringMap<>(DEFAULT_CAPACITY, NO_MAX_SLOTS);
  }

  /**
   * A new, uncapped map seeded at {@code capacity} (rounded up to a power of two on first write).
   * Use when the caller already knows the rough size; otherwise prefer {@link #createUncapped()} or
   * a {@link #adaptiveSizingHint()}.
   */
  @Nonnull
  public static <K, V> LightStringMap<K, V> createUncapped(int capacity) {
    return new LightStringMap<>(capacity, NO_MAX_SLOTS);
  }

  /**
   * A new map hard-capped at {@code maxCapacity} slots (rounded up to a power of two), seeded at
   * the default capacity (clamped to the cap). Once the table is physically full at the cap, {@link
   * #set} rejects a genuinely new key (returns {@code false}) instead of growing further -- a
   * thought-free way to bound worst-case memory without minting an {@link AdaptiveSizingHint}.
   */
  @Nonnull
  public static <K, V> LightStringMap<K, V> createCapped(int maxCapacity) {
    int max = EmbeddingSupport.roundUpToPow2(maxCapacity);
    int seed = Math.min(DEFAULT_CAPACITY, max);
    return new LightStringMap<>(seed, max);
  }

  /**
   * A new map seeded at {@code initialCapacity} and hard-capped at {@code maxCapacity} (both in
   * slots, each rounded up to a power of two). Like {@link #createCapped(int)} but with an explicit
   * seed. Throws {@link IllegalArgumentException} if the rounded seed exceeds the rounded cap.
   */
  @Nonnull
  public static <K, V> LightStringMap<K, V> createCapped(int initialCapacity, int maxCapacity) {
    int seed = EmbeddingSupport.roundUpToPow2(initialCapacity);
    int max = EmbeddingSupport.roundUpToPow2(maxCapacity);
    if (seed > max) {
      throw new IllegalArgumentException(
          "initialCapacity (" + seed + " slots) exceeds maxCapacity (" + max + " slots)");
    }
    return new LightStringMap<>(seed, max);
  }

  /**
   * A new map sized (and, if the hint carries a {@code maxCapacity}, capped) from {@code hint}.
   * Mint the hint once per construction site via {@link #adaptiveSizingHint()} or {@link
   * #adaptiveSizingHintBuilder()}, hold it in a {@code static final} field, and pass it to every map
   * at that site.
   */
  @Nonnull
  public static <K, V> LightStringMap<K, V> create(@Nonnull AdaptiveSizingHint hint) {
    return new LightStringMap<>(hint);
  }

  /**
   * Mints a self-tuning {@link AdaptiveSizingHint} for a single construction site. Hold it in a
   * {@code static final} field and pass it to every {@link #create(AdaptiveSizingHint)} at that
   * site; the map sizes itself from the hint and tunes the hint back on its own. The caller never
   * touches the hint again.
   */
  @Nonnull
  public static AdaptiveSizingHint adaptiveSizingHint() {
    return new AdaptiveSizingHint();
  }

  /**
   * Opens a builder for an {@link AdaptiveSizingHint} that carries an initial capacity and/or a
   * hard {@code maxCapacity}. Use this instead of {@link #adaptiveSizingHint()} when a site wants
   * to bound its maps' worst-case memory: every map built from the returned hint shares the same
   * cap, and {@link #set} rejects (returns {@code false}) once a map is physically full at that
   * cap. The hint still self-tunes its seed capacity within the cap.
   */
  @Nonnull
  public static AdaptiveSizingHintBuilder adaptiveSizingHintBuilder() {
    return new AdaptiveSizingHintBuilder();
  }

  /** Builds an {@link AdaptiveSizingHint} with an initial and/or maximum capacity. */
  public static final class AdaptiveSizingHintBuilder {
    private int initCapacity = DEFAULT_HINT_SLOTS;
    private int maxCapacity = NO_MAX_SLOTS;

    private AdaptiveSizingHintBuilder() {}

    /** Seed capacity in slots for a cold map (rounded up to a power of two). */
    @Nonnull
    public AdaptiveSizingHintBuilder initCapacity(int slots) {
      this.initCapacity = slots;
      return this;
    }

    /**
     * Hard cap, in slots (rounded up to a power of two), on how large any map built from this hint
     * may grow. Once a map is physically full at this many slots, {@link #set} rejects a new key
     * (returns {@code false}) instead of growing further -- bounding worst-case memory.
     */
    @Nonnull
    public AdaptiveSizingHintBuilder maxCapacity(int slots) {
      this.maxCapacity = slots;
      return this;
    }

    @Nonnull
    public AdaptiveSizingHint build() {
      int seed = EmbeddingSupport.roundUpToPow2(this.initCapacity);
      int max =
          (this.maxCapacity == NO_MAX_SLOTS)
              ? NO_MAX_SLOTS
              : EmbeddingSupport.roundUpToPow2(this.maxCapacity);
      if (max != NO_MAX_SLOTS && seed > max) {
        throw new IllegalArgumentException(
            "initCapacity (" + seed + " slots) exceeds maxCapacity (" + max + " slots)");
      }
      return new AdaptiveSizingHint(seed, max);
    }
  }

  /**
   * Stores {@code value} under {@code key}, growing the backing table if the probe-bound grow
   * trigger fires. Returns {@code true} if the mapping was stored (or overwrote an existing one).
   *
   * <p>Returns {@code false} only for a capped map (one built via {@link #createCapped(int)} /
   * {@link #createCapped(int, int)}, or from a {@link #adaptiveSizingHintBuilder()}.{@code
   * maxCapacity(...)} hint): once the table is physically full at its cap, a genuinely new key is
   * rejected rather than growing past the cap. The rejection is non-fatal -- the map is unchanged
   * and the caller may ignore the return. An uncapped map always returns {@code true}.
   */
  public boolean set(@Nonnull K key, @Nonnull V value) {
    // Null-value rejection is enforced centrally in EmbeddingSupport.setOrReject (the shared insert
    // core), so it holds for the spine entry points too -- not just this object-tier front door.
    // A thin delegate over the shared spine orchestration: it passes this map's cap (NO_MAX_SLOTS
    // when uncapped) and does the two things only the object tier can -- swap in the new backing
    // array and teach the sizing hint. A null result is the spine's non-fatal rejection signal.
    Object[] before = this.data;
    int beforeSlots = EmbeddingSupport.numSlots(before);
    Object[] after =
        EmbeddingSupport.setOrReject(this.initialCapacity, this.maxSlots, before, key, value);
    if (after == null) {
      return false; // capped, physically full, key is new
    }
    this.data = after;
    recordGrowth(beforeSlots, after);
    return true;
  }

  // Teach the sizing hint after a genuine grow (not the lazy first allocation, which seeds from
  // beforeSlots == 0) so it learns this site's high-water mark. seedSlots()/newMapData never feed
  // the hint.
  private void recordGrowth(int beforeSlots, @Nullable Object[] after) {
    if (this.sizingHint != null && beforeSlots != 0) {
      int afterSlots = EmbeddingSupport.numSlots(after);
      if (afterSlots > beforeSlots) {
        this.sizingHint.recordSlots(afterSlots);
      }
    }
  }

  @Nullable
  public V get(@Nonnull K key) {
    return EmbeddingSupport.get(this.data, key);
  }

  public void remove(@Nonnull K key) {
    EmbeddingSupport.remove(this.data, key);
  }

  /** The number of live entries in this map (tombstones excluded). */
  public int size() {
    return EmbeddingSupport.size(this.data);
  }

  public boolean containsKey(@Nonnull K key) {
    return EmbeddingSupport.containsKey(this.data, key);
  }

  @SuppressWarnings("unchecked")
  public void forEach(@Nonnull BiConsumer<? super K, ? super V> consumer) {
    Object[] mapData = this.data;
    if (mapData == null) return;
    int numSlots = mapData.length >> 1;
    for (int slot = 0; slot < numSlots; slot++) {
      Object key = mapData[slot];
      if (key == null || EmbeddingSupport.isRemoved(key)) continue;
      consumer.accept((K) key, (V) mapData[slot + numSlots]);
    }
  }

  // Visible for testing: the backing spine (null until the first set).
  @Nullable
  Object[] dataForTesting() {
    return this.data;
  }

  /**
   * A self-tuning, per-construction-site sizing estimate. Mint one via {@link
   * LightStringMap#adaptiveSizingHint()}, hold it in a {@code static final} field, and pass it to
   * {@link LightStringMap#create(AdaptiveSizingHint)}; the map reads it to size itself and tunes it
   * back as it grows. The caller never updates it.
   *
   * <p>Opaque by design (no public members). The estimate self-tunes on two events the map already
   * observes -- a new map is started ({@link #seedSlots()}) and a map grows ({@link
   * #recordSlots(int)}) -- so it is tier-agnostic: the same hint can drive both this object and the
   * static {@link EmbeddingSupport} spine.
   *
   * <p>Tuning is racy by design and needs no synchronization: {@code slots}/{@code constructs} are
   * plain ints, whose reads and writes are atomic (JLS 17.7), so a reader never sees a half-written
   * value. The only races are a lost update (an interleaved increment or grow dropped) and a stale
   * read (a write not yet visible to another thread); either just mis-sizes a future array by a
   * class (over/under-provision) for an instance or two, which the next grow corrects. Map data is
   * never touched by this state, so there is no corruption path.
   */
  public static final class AdaptiveSizingHint {
    // Learned seed capacity in slots (always a power of two). Additive-increase on grow (with one
    // class of headroom), multiplicative-decrease on the decay tick.
    private int slots;
    // Approximate count of maps started from this hint; drives the periodic step-down decay.
    private int constructs;
    // Hard cap on slots for maps built from this hint (a power of two), or NO_MAX_SLOTS when
    // uncapped. Immutable; the learned seed is clamped to it so a hint never over-provisions past
    // the cap.
    private final int maxSlots;

    private AdaptiveSizingHint() {
      this(DEFAULT_HINT_SLOTS, NO_MAX_SLOTS);
    }

    private AdaptiveSizingHint(int seedSlots, int maxSlots) {
      this.slots = seedSlots;
      this.maxSlots = maxSlots;
    }

    // The hard slot cap for maps built from this hint (NO_MAX_SLOTS when uncapped).
    int maxSlots() {
      return this.maxSlots;
    }

    /**
     * The seed capacity (in slots) for a map just started from this hint. Advances the decay clock
     * and, every {@link #DECAY_INTERVAL} maps, steps the estimate down one class so a stale
     * high-water from a past spike self-corrects; if that made it too tight, the next grow snaps it
     * back.
     */
    int seedSlots() {
      int n = ++this.constructs; // racy; a lost increment only jitters the decay cadence
      if ((n & (DECAY_INTERVAL - 1)) == 0) {
        int reduced = this.slots >> 1;
        this.slots = (reduced < MIN_HINT_SLOTS) ? MIN_HINT_SLOTS : reduced;
      }
      return this.slots;
    }

    /**
     * Records that a map grew to {@code grownSlots}. Reserves one extra power-of-two class so a
     * reseeded map starts with slack and is unlikely to immediately re-trip the grow trigger for
     * the same workload. Monotonic-max, clamped to {@link #MAX_HINT_SLOTS} and to this hint's hard
     * {@code maxSlots} cap so a capped hint never seeds a map larger than its cap.
     */
    void recordSlots(int grownSlots) {
      int candidate = grownSlots << 1;
      int ceiling = Math.min(MAX_HINT_SLOTS, this.maxSlots);
      if (candidate > ceiling) {
        candidate = ceiling;
      }
      if (candidate > this.slots) {
        this.slots = candidate;
      }
    }

    // Visible for testing: the current learned seed capacity in slots.
    int currentSeedSlots() {
      return this.slots;
    }
  }

  public static final class EmbeddingSupport {
    // findSlot is a pure lookup in the String.indexOf idiom: a non-negative slot on a hit, or this
    // sentinel on any miss (null map or absent key).
    public static final int SLOT_NOT_FOUND = -1;
    // findInsertionSlot is a locate-or-reserve in the Arrays.binarySearch idiom, so its return
    // lives
    // in a different numeric space than findSlot's: a non-negative slot when the key is already
    // present, a flip()-encoded free slot when it is absent, or this sentinel when the table is
    // physically full. The two sentinels are deliberately named apart so a return from one method
    // is
    // never compared against the other's contract.
    static final int SLOT_CAPACITY_REACHED = Integer.MIN_VALUE;

    // Grow trigger: an insertion that would land this many slots or more from its home slot forces
    // a resize, rather than waiting for the table to fill completely. This bounds the worst-case
    // probe length (and therefore lookup cost) directly -- a load-factor threshold cannot, because
    // it is blind to local clustering (colliding keys pile into one chain long before global
    // occupancy is high). The check is derived entirely from the probe walk, so it is stateless and
    // lives here in the spine rather than needing a maintained live count in the object tier.
    //
    // Chosen as a power-of-two-friendly 8 from measurement (LightStringMapGrowBenchmark): it caps
    // the worst-case probe at 8 while keeping the memory over-provision modest on well-spread keys.
    // Because a table never has more than (numSlots - 1) probe distance, this is inert for tables
    // of
    // 8 slots or fewer -- tiny maps still grow only when physically full, exactly as before.
    static final int MAX_PROBES = 8;

    // Backstop on probe-bound over-growth, so a hashCode() collision set cannot exhaust the heap.
    // Keys that share a hashCode() collapse onto one home slot in EVERY table size, so growing can
    // never shorten their probe chain. A pure probe-bound trigger would then double the table every
    // insert past MAX_PROBES -- a handful of colliding keys could balloon an uncapped map to
    // hundreds of millions of slots (an adversarial-input OOM). We therefore refuse a probe-bound
    // grow once the table already holds this many slots per live entry: past that point the long
    // chain is a genuine collision cluster no resize can spread, so we accept the chain (bounded
    // lookup cost) rather than grow (unbounded memory). Growth to make physical room is never gated
    // by this -- only the probe-bound trigger is. Memory stays O(live entries); the MAX_PROBES
    // probe-length bound still holds for well-distributed keys and degrades gracefully, not
    // catastrophically, only under genuine hashCode collisions.
    static final int MAX_SLOTS_PER_LIVE_ENTRY = 8;

    // The deletion tombstone. A dedicated singleton *type* rather than a magic String so it is
    // unmistakable in a heap dump or debugger (a Tombstone instance, not a String of NUL bytes) and
    // can never collide with a real key of any type. Compared only by identity (==).
    private static final class RemovedTombstone {
      static final RemovedTombstone INSTANCE = new RemovedTombstone();

      private RemovedTombstone() {}

      @Override
      public String toString() {
        return "--REMOVED--";
      }
    }

    static final Object REMOVED = RemovedTombstone.INSTANCE;

    @Nullable public static final Object[] EMPTY_DATA = null;

    public static boolean isDefinitelyEmpty(@Nullable Object[] mapData) {
      return (mapData == null);
    }

    public static int numSlots(@Nullable Object[] mapData) {
      return mapData == null ? 0 : mapData.length >> 1;
    }

    public static int size(@Nullable Object[] mapData) {
      if (mapData == null) return 0;

      int size = 0;
      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        Object key = mapData[slot];
        if (key != null && key != REMOVED) size += 1;
      }
      return size;
    }

    @Nullable
    public static Object[] clear(@Nullable Object[] mapData) {
      if (mapData != null) {
        Arrays.fill(mapData, null);
      }
      return mapData;
    }

    @Nullable
    public static Object[] copy(@Nullable Object[] mapData) {
      return mapData == null ? null : mapData.clone();
    }

    public static boolean isPresent(int slotIndex) {
      return (slotIndex >= 0);
    }

    public static boolean isRemoved(@Nullable Object key) {
      return (key == REMOVED);
    }

    @Nullable
    public static Object keyAt(@Nullable Object[] mapData, int slotIndex) {
      if (mapData == null) return null;
      if (slotIndex < 0) return null;

      Object key = mapData[slotIndex];
      return (key == REMOVED) ? null : key;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <V> V valueAt(@Nullable Object[] mapData, int slotIndex) {
      if (mapData == null || slotIndex < 0) return null;

      return (V) mapData[slotIndex + numSlots(mapData)];
    }

    public static final boolean containsKey(@Nullable Object[] mapData, @Nonnull Object key) {
      if (mapData == null) return false;

      int numSlots = numSlots(mapData);

      int hash = key.hashCode();
      int preferredSlot = preferredSlot(numSlots, hash);

      // Identity fast path before equals(), same rationale as findSlot.
      for (int slot = preferredSlot; slot < numSlots; ++slot) {
        Object curKey = mapData[slot];
        if (curKey == null) return false;
        if (curKey == key) return true;
        if (curKey != REMOVED && key.equals(curKey)) return true;
      }
      for (int slot = 0; slot < preferredSlot; ++slot) {
        Object curKey = mapData[slot];
        if (curKey == null) return false;
        if (curKey == key) return true;
        if (curKey != REMOVED && key.equals(curKey)) return true;
      }
      return false;
    }

    public static final boolean containsValue(@Nullable Object[] mapData, @Nonnull Object value) {
      if (mapData == null) return false;

      for (int valueIndex = numSlots(mapData); valueIndex < mapData.length; ++valueIndex) {
        Object curValue = mapData[valueIndex];
        if (value.equals(curValue)) return true;
      }
      return false;
    }

    @Nonnull
    public static <V> Object[] set(
        @Nullable Object[] mapData, @Nonnull Object key, @Nonnull V value) {
      return set(DEFAULT_CAPACITY, mapData, key, value);
    }

    @Nonnull
    public static <V> Object[] set(
        int initialCapacity, @Nullable Object[] mapData, @Nonnull Object key, @Nonnull V value) {
      // Uncapped: NO_MAX_SLOTS makes the cap check inert, so setOrReject grows on demand and never
      // rejects -- the result is always non-null.
      return setOrReject(initialCapacity, NO_MAX_SLOTS, mapData, key, value);
    }

    /**
     * Hint-aware spine insert: the migration counterpart of {@link LightStringMap#set} for a map
     * that has been dropped to the embedded spine but wants to keep the self-tuning it had as an
     * object. Seeds a fresh table from {@code hint.seedSlots()} and teaches the hint back on a
     * genuine grow, exactly as the object tier does -- so graduating a map to the spine is a strict
     * superset (it gains the embedding win without losing its sizing).
     *
     * <p>Unlike the object tier, the hint's {@code maxCapacity} cap does <em>not</em> bound growth
     * here: a returned {@code Object[]} cannot signal a rejection, so this always stores and always
     * returns non-null. Taking the spine means you own bounding your own growth; only the sizing
     * tuning follows down, not the cap guardrail.
     */
    @Nonnull
    public static <V> Object[] set(
        @Nonnull AdaptiveSizingHint hint,
        @Nullable Object[] mapData,
        @Nonnull Object key,
        @Nonnull V value) {
      int beforeSlots = numSlots(mapData);
      // Seed a fresh table from the hint (mirrors LightStringMap(hint)); initialCapacity is only
      // read on the null-array branch, so the 0 below is never consumed when mapData is non-null.
      int seedCapacity = (mapData == null) ? hint.seedSlots() : 0;
      Object[] after = setOrReject(seedCapacity, NO_MAX_SLOTS, mapData, key, value);
      // Teach the hint on a genuine grow only (not the lazy first allocation, which already seeded
      // from the hint) -- mirrors LightStringMap.recordGrowth.
      if (beforeSlots != 0) {
        int afterSlots = numSlots(after);
        if (afterSlots > beforeSlots) {
          hint.recordSlots(afterSlots);
        }
      }
      return after;
    }

    // The single insert orchestration shared by the uncapped spine set() above and the capped
    // object-tier LightStringMap.set(): probe, then either overwrite in place, fill a free slot, or
    // grow. Stores {@code key -> value} and returns the (possibly new) backing array.
    //
    // Returns null ONLY when {@code maxSlots} is a finite cap, the table is physically full at it,
    // and the key is genuinely new -- the caller's non-fatal rejection signal. With {@code maxSlots
    // == NO_MAX_SLOTS} the cap check is inert (numSlots is always below it), so a grow is always
    // available and the result is never null.
    @Nullable
    static <V> Object[] setOrReject(
        int initialCapacity,
        int maxSlots,
        @Nullable Object[] mapData,
        @Nonnull Object key,
        @Nonnull V value) {
      // The map contract forbids null values (a null get() unambiguously means "absent"), so reject
      // one here -- the single chokepoint every set path (object tier and spine) flows through.
      Objects.requireNonNull(value, "value");
      if (mapData == null) {
        return newMapData(initialCapacity, key, value);
      }

      int numSlots = numSlots(mapData);
      // Compute the home slot once and thread it into the probe (findInsertionSlot) and the
      // probe-bound distance check below, rather than re-deriving it from key.hashCode() twice.
      int home = preferredSlot(numSlots, key.hashCode());
      int slot = findInsertionSlot(mapData, numSlots, key, home);
      if (slot >= 0) {
        // Key already present -- overwrite in place, no growth.
        mapData[slot + numSlots] = value;
        return mapData;
      }

      if (slot == SLOT_CAPACITY_REACHED) {
        // Physically full (no null and no reclaimable tombstone). We must grow to make room, unless
        // a finite cap blocks it -- then reject the new key (non-fatal, map unchanged).
        if (numSlots < maxSlots) {
          mapData = expandMapData(mapData);
          newMapUncheckedInsert(mapData, numSlots(mapData), key, value);
          return mapData;
        }
        return null;
      }

      // A free slot the probe walk found. Grow only if the insertion is past the probe bound AND a
      // grow could actually help. Keys sharing a hashCode() cluster onto one chain in every table
      // size, so once the table already holds MAX_SLOTS_PER_LIVE_ENTRY slots per live entry no
      // resize can spread them -- we accept the long chain instead of doubling the table forever
      // (the adversarial-input OOM backstop). At a finite cap the bound is likewise relaxed: we
      // cannot grow, so we fill past MAX_PROBES until physically full.
      int availableSlot = flip(slot);
      int distance = (availableSlot - home) & (numSlots - 1);
      if (distance >= MAX_PROBES
          && numSlots < maxSlots
          && (long) numSlots < (long) size(mapData) * MAX_SLOTS_PER_LIVE_ENTRY) {
        // Grow, then insert into the fresh (tombstone-free, better-spread) table.
        mapData = expandMapData(mapData);
        newMapUncheckedInsert(mapData, numSlots(mapData), key, value);
        return mapData;
      }
      mapData[availableSlot] = key;
      mapData[availableSlot + numSlots] = value;
      return mapData;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <V> V get(@Nullable Object[] mapData, @Nonnull Object key) {
      if (mapData == null) return null;

      int numSlots = numSlots(mapData);
      int foundIndex = findSlot(mapData, numSlots, key);

      return (foundIndex >= 0) ? (V) mapData[numSlots + foundIndex] : null;
    }

    public static boolean remove(@Nullable Object[] mapData, @Nonnull Object key) {
      if (mapData == null) return false;

      int numSlots = numSlots(mapData);
      int foundIndex = findSlot(mapData, numSlots, key);
      if (foundIndex >= 0) {
        mapData[foundIndex] = REMOVED;
        mapData[foundIndex + numSlots] = null;

        return true;
      } else {
        return false;
      }
    }

    public static <T> int findInsertionSlot(@Nullable Object[] mapData, @Nonnull Object key) {
      if (mapData == null) return SLOT_CAPACITY_REACHED;

      return findInsertionSlot(mapData, numSlots(mapData), key);
    }

    @Nonnull
    public static final Object[] insertAt(
        int initialCapacity,
        @Nullable Object[] mapData,
        int insertionSlot,
        @Nonnull Object key,
        @Nonnull Object value) {
      // Same null-value invariant as setOrReject; insertAt is a separate spine write path that does
      // not flow through it, so it needs its own guard.
      Objects.requireNonNull(value, "value");
      if (mapData == null) {
        return newMapData(initialCapacity, key, value);
      }

      if (insertionSlot == SLOT_CAPACITY_REACHED) {
        mapData = expandMapData(mapData);
        newMapUncheckedInsert(mapData, numSlots(mapData), key, value);
      } else {
        if (insertionSlot < 0) insertionSlot = flip(insertionSlot);

        mapData[insertionSlot] = key;
        mapData[insertionSlot + numSlots(mapData)] = value;
      }

      return mapData;
    }

    static final <T> int findInsertionSlot(
        @Nonnull Object[] mapData, int numSlots, @Nonnull Object key) {
      return findInsertionSlot(mapData, numSlots, key, preferredSlot(numSlots, key.hashCode()));
    }

    static final <T> int findInsertionSlot(
        @Nonnull Object[] mapData, int numSlots, @Nonnull Object key, int preferredSlot) {
      int availableIndex = SLOT_CAPACITY_REACHED;
      for (int keyIndex = preferredSlot; keyIndex < numSlots; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        // A reclaimable tombstone seen earlier in the probe order beats this null: it is closer to
        // the home slot (shorter displacement, so less likely to trip the probe-bound grow) and
        // reusing it clears a tombstone. The key is confirmed absent either way (we reached a
        // null).
        if (curKey == null)
          return (availableIndex != SLOT_CAPACITY_REACHED) ? availableIndex : flip(keyIndex);
        if (curKey == key) return keyIndex;
        if (curKey == REMOVED) {
          if (availableIndex == SLOT_CAPACITY_REACHED) availableIndex = flip(keyIndex);
        } else if (key.equals(curKey)) return keyIndex;
      }
      for (int keyIndex = 0; keyIndex < preferredSlot; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        if (curKey == null)
          return (availableIndex != SLOT_CAPACITY_REACHED) ? availableIndex : flip(keyIndex);
        if (curKey == key) return keyIndex;
        if (curKey == REMOVED) {
          if (availableIndex == SLOT_CAPACITY_REACHED) availableIndex = flip(keyIndex);
        } else if (key.equals(curKey)) return keyIndex;
      }
      return availableIndex;
    }

    // Encodes a free slot index as a negative number so it is distinguishable from a "key present
    // at this slot" hit (which is >= 0), letting one int carry both outcomes without an out-param.
    // This is the same convention java.util.Arrays.binarySearch uses to return an insertion point:
    // slot i maps to -i-1, so slot 0 (which cannot be negated to a distinct value) becomes -1.
    // Self-inverse: flip(flip(i)) == i.
    static int flip(int keyIndex) {
      return -keyIndex - 1;
    }

    public static final void removeAt(@Nullable Object[] mapData, int slot) {
      if (mapData == null || slot < 0) return;

      mapData[slot] = REMOVED;
      mapData[slot + numSlots(mapData)] = null;
    }

    @Nullable
    public static final Object getAndRemoveAt(@Nullable Object[] mapData, int slot) {
      if (mapData == null || slot < 0) return null;

      mapData[slot] = REMOVED;

      int valueIndex = slot + numSlots(mapData);
      Object prev = mapData[valueIndex];
      mapData[valueIndex] = null;

      return prev;
    }

    public static final int findSlot(@Nullable Object[] mapData, @Nonnull Object key) {
      if (mapData == null) return SLOT_NOT_FOUND;

      return findSlot(mapData, numSlots(mapData), key);
    }

    static final int findSlot(@Nonnull Object[] mapData, int numSlots, @Nonnull Object key) {
      int hash = key.hashCode();
      int preferredSlot = preferredSlot(numSlots, hash);

      // A single probe that terminates at the first null slot. Each live slot is checked by
      // identity (curKey == key) before equals(): interned or reused keys -- the common case for a
      // String-keyed map here -- hit without any virtual equals() call, and the guard is a wash
      // when equals() inlines and a win when it does not (keys erase to Object, so equals() is not
      // guaranteed monomorphic across callers of the shared spine). We compare key.equals(curKey),
      // not curKey.equals(key), so the receiver stays the caller's key type and C2 can devirtualize
      // it. This is a per-slot guard, not a whole-array pre-pass: a miss still touches only the
      // probe chain. A live key is never REMOVED, so the identity hit needs no tombstone check.
      for (int keyIndex = preferredSlot; keyIndex < numSlots; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        if (curKey == null) return SLOT_NOT_FOUND;
        if (curKey == key) return keyIndex;
        if (curKey != REMOVED && key.equals(curKey)) return keyIndex;
      }
      for (int keyIndex = 0; keyIndex < preferredSlot; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        if (curKey == null) return SLOT_NOT_FOUND;
        if (curKey == key) return keyIndex;
        if (curKey != REMOVED && key.equals(curKey)) return keyIndex;
      }
      return SLOT_NOT_FOUND;
    }

    @Nonnull
    static final Object[] newMapData(
        int initialCapacity, @Nonnull Object key, @Nonnull Object value) {
      int numSlots = roundUpToPow2(initialCapacity);
      Object[] mapData = new Object[numSlots << 1];

      int slotIndex = preferredSlot(numSlots, key.hashCode());
      mapData[slotIndex] = key;
      mapData[slotIndex + numSlots] = value;
      return mapData;
    }

    @Nonnull
    public static final Object[] expandMapData(@Nonnull Object[] mapData) {
      // Subtle - capacity is in terms of slots - not array size, so passing length is
      // asking to double capacity
      return expandMapData(mapData, mapData.length);
    }

    @Nonnull
    public static Object[] expandMapData(@Nonnull Object[] origMapData, int newCapacity) {
      newCapacity = roundUpToPow2(newCapacity);
      int newSize = newCapacity << 1;
      // Don't try to optimize by returning origMapData if big enough to contain
      // the newCapacity.  There's subtle invariant that new maps also don't
      // contain remove markers that must also be maintained.

      int origNumSlots = numSlots(origMapData);

      Object[] newMapData = new Object[newSize];
      for (int slot = 0; slot < origNumSlots; ++slot) {
        Object key = origMapData[slot];
        if (key == null || key == REMOVED) continue;

        Object value = origMapData[slot + origNumSlots];
        newMapUncheckedInsert(newMapData, newCapacity, (String) key, value);
      }
      return newMapData;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> void forEach(
        @Nullable Object[] mapData, @Nonnull BiConsumer<? super K, ? super V> entryConsumer) {
      if (mapData == null) return;

      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        Object key = mapData[slot];
        if (key == null || key == REMOVED) continue;

        Object value = mapData[slot + numSlots];
        entryConsumer.accept((K) key, (V) value);
      }
    }

    @SuppressWarnings("unchecked")
    public static <C, K, V> void forEach(
        @Nullable Object[] mapData,
        @Nullable C ctx,
        @Nonnull TriConsumer<? super C, ? super K, ? super V> entryConsumer) {
      if (mapData == null) return;

      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        Object key = mapData[slot];
        if (key == null || key == REMOVED) continue;

        Object value = mapData[slot + numSlots];
        entryConsumer.accept(ctx, (K) key, (V) value);
      }
    }

    @Nonnull
    public static String toInternalString(@Nullable Object[] mapData) {
      StringBuilder builder = new StringBuilder(128);
      builder.append('{');

      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        Object key = mapData[slot];
        if (key == null) continue;

        builder.append('[').append(slot).append("]=");

        if (key == REMOVED) {
          builder.append("--REMOVED--");
        } else {
          Object value = mapData[slot + numSlots];
          builder.append(key).append(':').append(value);
        }
        builder.append('\n');
      }
      builder.append('}');
      return builder.toString();
    }

    static void newMapUncheckedInsert(
        @Nonnull Object[] mapData, int numSlots, @Nonnull Object key, @Nonnull Object value) {
      int hash = key.hashCode();
      int preferredSlot = preferredSlot(numSlots, hash);

      for (int slot = preferredSlot; slot < numSlots; ++slot) {
        Object curKey = mapData[slot];
        if (curKey == null) {
          mapData[slot] = key;
          mapData[slot + numSlots] = value;

          return;
        }
      }
      for (int slot = 0; slot < preferredSlot; ++slot) {
        Object curKey = mapData[slot];
        if (curKey == null) {
          mapData[slot] = key;
          mapData[slot + numSlots] = value;

          return;
        }
      }
    }

    static int preferredSlot(int numSlots, int hash) {
      // numSlots is required to be a power of 2 (allocation sites round up via roundUpToPow2).
      // The bit mask is ~20x faster than a modulo divide on typical hardware.
      return (hash ^ (hash >>> 16)) & (numSlots - 1);
    }

    static int roundUpToPow2(int n) {
      return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }
  }
}
