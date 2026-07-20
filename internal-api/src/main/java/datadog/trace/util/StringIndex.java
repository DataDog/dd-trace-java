package datadog.trace.util;

import java.lang.reflect.Array;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Flat open-addressed name set. Generic — it knows only names.
 *
 * <p>Two ways to use it, trading convenience for indirection:
 *
 * <ul>
 *   <li>{@link EmbeddingSupport} — static algorithm over <b>raw arrays</b> you <b>embed</b> in your
 *       own (ideally {@code static final}) fields; the JIT folds the refs to constants. The fastest
 *       path, nothing to dereference. (Named for the same role as {@code
 *       LightMap.EmbeddingSupport}: the static ops you reach for when you own the backing arrays
 *       directly.)
 *   <li>The {@code StringIndex} <b>instance</b> ({@link #of}) — a convenience wrapper holding the
 *       arrays; {@link #indexOf}/{@link #contains} delegate to {@link EmbeddingSupport}. Costs an
 *       instance-field load per call (the indirection the static path removes) — fine off the hot
 *       path.
 * </ul>
 *
 * <p>Consumers attach their own parallel payload arrays (ids, values, ...) sized to {@link
 * #numSlots()} and indexed by the slot {@code indexOf} returns. {@code mapValues}/{@code
 * mapIntValues}/{@code mapLongValues} build such an array at construction; {@code lookup}/{@code
 * lookupOrDefault} read one back in a single call (slot resolve + array read).
 *
 * <p>Slot 0-value is the empty sentinel: {@link EmbeddingSupport#hash} never returns 0, so {@code
 * hashes[i] == 0} unambiguously means an empty slot.
 *
 * <p>Trades memory for simplicity (and, incidentally, speed). The table is 2x-oversized ({@link
 * EmbeddingSupport#DEFAULT_LOAD_FACTOR} &le; 0.5) so build-time placement always finds a free slot
 * and never has to rehash or resize — short probe chains are a welcome side effect, not the design
 * goal. The cached {@code int[]} hashes gate {@code equals()}. Both cost memory, so a
 * tightly-packed set is more compact: prefer {@link java.util.Set#copyOf} (the JDK's {@code SetN})
 * when you only need membership, and reach for {@code StringIndex} for the {@code
 * indexOf}-&gt;parallel-array (name&rarr;id) capability or the hot, allocation-free static {@link
 * EmbeddingSupport} path. (If footprint matters more than build simplicity, build via {@link
 * EmbeddingSupport#capacityFor(int, float)} at a higher load factor — placement still finds a slot
 * at any factor &lt; 1, so no rehash is needed.)
 */
public final class StringIndex {
  private final int[] hashes;
  private final String[] names;

  private StringIndex(int[] hashes, String[] names) {
    this.hashes = hashes;
    this.names = names;
  }

  /**
   * Convenience instance — wraps the placed arrays. For the hot path prefer raw {@link
   * EmbeddingSupport}.
   */
  public static StringIndex of(String... names) {
    Data data = EmbeddingSupport.create(names);
    return new StringIndex(data.hashes, data.names);
  }

  /**
   * Slot of {@code name}, or -1. Delegates to {@link EmbeddingSupport} on the instance's arrays.
   */
  public int indexOf(String name) {
    return EmbeddingSupport.indexOf(this.hashes, this.names, name);
  }

  public boolean contains(String name) {
    return indexOf(name) >= 0;
  }

  /** Table size — allocate parallel payload arrays of this length. */
  public int numSlots() {
    return hashes.length;
  }

  // --- value mapping: build a slot-aligned parallel array (off the hot path) ---

  /**
   * Builds a slot-aligned {@code T[]} of values: {@code out[indexOf(name)] == fn.apply(name)} for
   * every indexed name; other slots stay {@code null}. {@code type} is the array element type (Java
   * can't allocate a generic array without it). Pair with {@link #lookup(Object[], String)}.
   */
  public <T> T[] mapValues(Class<T> type, Function<String, T> fn) {
    return EmbeddingSupport.mapValues(this.names, type, fn);
  }

  /** Slot-aligned {@code int[]} of values; absent slots stay 0. See {@link #mapValues}. */
  public int[] mapIntValues(ToIntFunction<String> fn) {
    return EmbeddingSupport.mapIntValues(this.names, fn);
  }

  /** Slot-aligned {@code long[]} of values; absent slots stay 0. See {@link #mapValues}. */
  public long[] mapLongValues(ToLongFunction<String> fn) {
    return EmbeddingSupport.mapLongValues(this.names, fn);
  }

  // --- lookup: resolve a key and read its parallel value in one call ---

  /** {@code data[indexOf(key)]}, or {@code null} when {@code key} is absent. */
  public <T> T lookup(T[] data, String key) {
    return EmbeddingSupport.lookup(this.hashes, this.names, data, key);
  }

  /** {@code data[indexOf(key)]}, or {@code defaultValue} when {@code key} is absent. */
  public <T> T lookupOrDefault(T[] data, String key, T defaultValue) {
    return EmbeddingSupport.lookupOrDefault(this.hashes, this.names, data, key, defaultValue);
  }

  /** {@code data[indexOf(key)]}, or 0 when {@code key} is absent. */
  public int lookup(int[] data, String key) {
    return EmbeddingSupport.lookup(this.hashes, this.names, data, key);
  }

  /** {@code data[indexOf(key)]}, or {@code defaultValue} when {@code key} is absent. */
  public int lookupOrDefault(int[] data, String key, int defaultValue) {
    return EmbeddingSupport.lookupOrDefault(this.hashes, this.names, data, key, defaultValue);
  }

  /** {@code data[indexOf(key)]}, or 0 when {@code key} is absent. */
  public long lookup(long[] data, String key) {
    return EmbeddingSupport.lookup(this.hashes, this.names, data, key);
  }

  /** {@code data[indexOf(key)]}, or {@code defaultValue} when {@code key} is absent. */
  public long lookupOrDefault(long[] data, String key, long defaultValue) {
    return EmbeddingSupport.lookupOrDefault(this.hashes, this.names, data, key, defaultValue);
  }

  /** Build-time carrier. Pull the fields into your own (static final) fields; don't keep this. */
  public static final class Data {
    public final int[] hashes;
    public final String[] names;

    Data(int[] hashes, String[] names) {
      this.hashes = hashes;
      this.names = names;
    }
  }

  /**
   * Static algorithm over raw arrays. Query helpers take raw arrays, never a Data or a StringIndex.
   */
  public static final class EmbeddingSupport {
    private EmbeddingSupport() {}

    /** Spread of String.hashCode; 0 reserved as the empty sentinel. */
    public static int hash(String name) {
      int h = name.hashCode(); // cached on String -> field load
      return h == 0 ? 0xDD06 : h ^ (h >>> 16);
    }

    /**
     * Balanced default load factor — target fill {@code <= 0.5} ({@code >= 2x} capacity). (Mirrors
     * {@code FlatHashtable.DEFAULT_LOAD_FACTOR}; duplicated while the two are separate PRs, to be
     * unified when the flat-collection family converges.)
     */
    public static final float DEFAULT_LOAD_FACTOR = 0.5f;

    /** Sparse load factor — target fill {@code <= 0.25} ({@code >= 4x} capacity). */
    public static final float LOW_LOAD_FACTOR = 0.25f;

    /** Power-of-two capacity for {@code n} names at the {@link #DEFAULT_LOAD_FACTOR}. */
    public static int capacityFor(int n) {
      return capacityFor(n, DEFAULT_LOAD_FACTOR);
    }

    /**
     * Power-of-two capacity for {@code n} names at {@code loadFactor}: the smallest power of two
     * {@code >= ceil(n / loadFactor)} (so the achieved fill is {@code <= loadFactor}). {@code n ==
     * 0} yields a minimal 2-slot table (StringIndex allows the empty set, unlike FlatHashtable).
     */
    public static int capacityFor(int n, float loadFactor) {
      if (n < 0) {
        throw new IllegalArgumentException("n must be non-negative: " + n);
      }
      if (!(loadFactor > 0f && loadFactor < 1f)) {
        throw new IllegalArgumentException("loadFactor must be in (0, 1): " + loadFactor);
      }
      if (n == 0) {
        return 2; // empty set -> minimal table (one always-empty slot suffices, 2 keeps it pow2)
      }
      int min = (int) Math.ceil(n / (double) loadFactor);
      return Integer.highestOneBit(min - 1) << 1;
    }

    /** Build the placed table. Returns a Data carrier; pull its arrays into your own fields. */
    public static Data create(String... names) {
      int size = capacityFor(names.length);
      int[] hashes = new int[size];
      String[] placed = new String[size];
      for (String name : names) {
        put(hashes, placed, name, hash(name));
      }
      return new Data(hashes, placed);
    }

    /**
     * Slot-aligned {@code T[]} over placed {@code names}: {@code out[slot] = fn(name)} per name,
     * {@code null} elsewhere. {@code type} is the array element type (generic-array allocation).
     */
    @SuppressWarnings("unchecked")
    public static <T> T[] mapValues(String[] names, Class<T> type, Function<String, T> fn) {
      T[] out = (T[]) Array.newInstance(type, names.length);
      for (int slot = 0; slot < names.length; slot++) {
        String name = names[slot];
        if (name != null) {
          out[slot] = fn.apply(name);
        }
      }
      return out;
    }

    /**
     * Slot-aligned {@code int[]} over placed {@code names}; {@code out[slot] = fn(name)}, 0 else.
     */
    public static int[] mapIntValues(String[] names, ToIntFunction<String> fn) {
      int[] out = new int[names.length];
      for (int slot = 0; slot < names.length; slot++) {
        String name = names[slot];
        if (name != null) {
          out[slot] = fn.applyAsInt(name);
        }
      }
      return out;
    }

    /**
     * Slot-aligned {@code long[]} over placed {@code names}; {@code out[slot] = fn(name)}, 0 else.
     */
    public static long[] mapLongValues(String[] names, ToLongFunction<String> fn) {
      long[] out = new long[names.length];
      for (int slot = 0; slot < names.length; slot++) {
        String name = names[slot];
        if (name != null) {
          out[slot] = fn.applyAsLong(name);
        }
      }
      return out;
    }

    /** Build-time placement. Returns the slot. */
    public static int put(int[] hashes, String[] names, String name, int h) {
      final int mask = hashes.length - 1;
      int i = h & mask;
      for (int probes = 0; probes <= mask; probes++, i = (i + 1) & mask) {
        if (hashes[i] == 0) {
          hashes[i] = h;
          names[i] = name;
          return i;
        }
        if (hashes[i] == h && names[i].equals(name)) {
          return i; // already present
        }
      }
      throw new IllegalStateException("table full"); // impossible at LF <= 0.5
    }

    /** Probe; returns the slot or -1. Raw arrays — no Data, no instance. */
    public static int indexOf(int[] hashes, String[] names, String name, int h) {
      final int mask = hashes.length - 1;
      int i = h & mask;
      for (int probes = 0; probes <= mask; probes++, i = (i + 1) & mask) {
        int sh = hashes[i];
        if (sh == 0) {
          return -1;
        }
        if (sh == h && names[i].equals(name)) {
          return i;
        }
      }
      return -1;
    }

    public static int indexOf(int[] hashes, String[] names, String name) {
      return indexOf(hashes, names, name, hash(name));
    }

    /** Number of slots — the length to size parallel payload arrays to. */
    public static int numSlots(int[] hashes) {
      return hashes.length;
    }

    /** {@code data[indexOf(...)]}, or {@code null} when {@code key} is absent. */
    public static <T> T lookup(int[] hashes, String[] names, T[] data, String key) {
      int slot = indexOf(hashes, names, key);
      return slot >= 0 ? data[slot] : null;
    }

    /** {@code data[indexOf(...)]}, or {@code defaultValue} when {@code key} is absent. */
    public static <T> T lookupOrDefault(
        int[] hashes, String[] names, T[] data, String key, T defaultValue) {
      int slot = indexOf(hashes, names, key);
      return slot >= 0 ? data[slot] : defaultValue;
    }

    /** {@code data[indexOf(...)]}, or 0 when {@code key} is absent. */
    public static int lookup(int[] hashes, String[] names, int[] data, String key) {
      int slot = indexOf(hashes, names, key);
      return slot >= 0 ? data[slot] : 0;
    }

    /** {@code data[indexOf(...)]}, or {@code defaultValue} when {@code key} is absent. */
    public static int lookupOrDefault(
        int[] hashes, String[] names, int[] data, String key, int defaultValue) {
      int slot = indexOf(hashes, names, key);
      return slot >= 0 ? data[slot] : defaultValue;
    }

    /** {@code data[indexOf(...)]}, or 0 when {@code key} is absent. */
    public static long lookup(int[] hashes, String[] names, long[] data, String key) {
      int slot = indexOf(hashes, names, key);
      return slot >= 0 ? data[slot] : 0L;
    }

    /** {@code data[indexOf(...)]}, or {@code defaultValue} when {@code key} is absent. */
    public static long lookupOrDefault(
        int[] hashes, String[] names, long[] data, String key, long defaultValue) {
      int slot = indexOf(hashes, names, key);
      return slot >= 0 ? data[slot] : defaultValue;
    }
  }
}
