package datadog.trace.util;

import java.util.function.ToIntFunction;

/**
 * Flat open-addressed name set. Generic — it knows only names.
 *
 * <p>Three ways to use it, trading convenience for indirection:
 *
 * <ul>
 *   <li>{@link Support} — static algorithm over <b>raw arrays</b>. Keep the arrays in your own
 *       (ideally {@code static final}) fields and the JIT folds the refs to constants. The fastest
 *       path; nothing to dereference.
 *   <li>{@link Data} — a <b>build-time carrier</b> for the placed {@code {hashes, names}} returned
 *       by {@link Support#create}. Pull its fields into your own and discard it.
 *   <li>The {@code StringIndex} <b>instance</b> ({@link #of}) — a convenience wrapper holding the
 *       arrays; {@link #indexOf}/{@link #contains} delegate to {@link Support}. Costs an
 *       instance-field load per call (the indirection the static path removes) — fine off the hot
 *       path.
 * </ul>
 *
 * <p>Consumers attach their own parallel payload arrays (ids, values, ...) sized to {@link #slots}
 * and indexed by the slot {@code indexOf} returns.
 *
 * <p>Slot 0-value is the empty sentinel: {@link Support#hash} never returns 0, so {@code hashes[i]
 * == 0} unambiguously means an empty slot.
 *
 * <p>Trades memory for simplicity (and, incidentally, speed). The table is 2x-oversized (load
 * factor &le; 0.5) so build-time placement always finds a free slot and never has to rehash or
 * resize — short probe chains are a welcome side effect, not the design goal. The cached {@code
 * int[]} hashes gate {@code equals()}. Both cost memory, so a tightly-packed set is more compact:
 * prefer {@link java.util.Set#copyOf} (the JDK's {@code SetN}) when you only need membership, and
 * reach for {@code StringIndex} for the {@code indexOf}-&gt;parallel-array (name&rarr;id)
 * capability or the hot, allocation-free static {@link Support} path. (If footprint ever matters
 * more than build simplicity, a higher load factor with construction-time rehashing would close the
 * gap.)
 */
public final class StringIndex {
  private final int[] hashes;
  private final String[] names;
  public final int slots; // == hashes.length

  private StringIndex(int[] hashes, String[] names) {
    this.hashes = hashes;
    this.names = names;
    this.slots = hashes.length;
  }

  /**
   * Convenience instance — wraps the placed arrays. For the hot path prefer raw {@link Support}.
   */
  public static StringIndex of(String... names) {
    Data data = Support.create(names);
    return new StringIndex(data.hashes, data.names);
  }

  /** Slot of {@code name}, or -1. Delegates to {@link Support} on the instance's arrays. */
  public int indexOf(String name) {
    return Support.indexOf(this.hashes, this.names, name);
  }

  public boolean contains(String name) {
    return indexOf(name) >= 0;
  }

  /** Table size — allocate parallel payload arrays of this length. */
  public int slots() {
    return this.slots;
  }

  /**
   * Builds a slot-aligned value array: {@code out[indexOf(name)] == fn.applyAsInt(name)} for every
   * indexed name. Pair with {@link #indexOf} to use this StringIndex as a string-&gt;int map
   * without per-lookup hashing. Empty slots hold 0 and are never read ({@code indexOf} returns -1
   * for non-members).
   */
  public int[] mapValues(ToIntFunction<String> fn) {
    return Support.mapValues(this.names, fn);
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
  public static final class Support {
    private Support() {}

    /** Spread of String.hashCode; 0 reserved as the empty sentinel. */
    public static int hash(String name) {
      int h = name.hashCode(); // cached on String -> field load
      return h == 0 ? 0xDD06 : h ^ (h >>> 16);
    }

    /** Power-of-two size, 2x-oversized so load factor stays &lt;= 0.5. */
    public static int tableSizeFor(int n) {
      int size = 1;
      while (size <= n) {
        size <<= 1;
      }
      return size << 1;
    }

    /** Build the placed table. Returns a Data carrier; pull its arrays into your own fields. */
    public static Data create(String... names) {
      int size = tableSizeFor(names.length);
      int[] hashes = new int[size];
      String[] placed = new String[size];
      for (String name : names) {
        put(hashes, placed, name, hash(name));
      }
      return new Data(hashes, placed);
    }

    /**
     * Slot-aligned value array over placed {@code names}; {@code out[slot] = fn(name)} per name.
     */
    public static int[] mapValues(String[] names, ToIntFunction<String> fn) {
      int[] out = new int[names.length];
      for (int slot = 0; slot < names.length; slot++) {
        String name = names[slot];
        if (name != null) {
          out[slot] = fn.applyAsInt(name);
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
        if (hashes[i] == h && eq(names[i], name)) {
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
        if (sh == h && eq(names[i], name)) {
          return i;
        }
      }
      return -1;
    }

    public static int indexOf(int[] hashes, String[] names, String name) {
      return indexOf(hashes, names, name, hash(name));
    }

    // `a` is a stored name on an occupied slot (never null); `b` is a non-null query.
    private static boolean eq(String a, String b) {
      return a == b || a.equals(b); // interned literals hit the == fast path
    }
  }
}
