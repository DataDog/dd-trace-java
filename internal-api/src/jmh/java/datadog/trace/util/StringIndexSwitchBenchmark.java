package datadog.trace.util;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The third {@link StringIndex} use case: replacing a {@code switch} over interned {@code String}
 * literals that maps a key to a small {@code int} id (exactly what {@code TagInterceptor} does to
 * decide whether/how to intercept a tag). Both forms resolve a key to an id, 0 == "not found".
 *
 * <p>Compared:
 *
 * <ul>
 *   <li>{@code switch} — a hand-written {@code switch(key)} over the literals ({@code hashCode}
 *       switch + {@code equals}), {@code default} returns 0.
 *   <li>{@code stringIndex} — {@code IDS[Support.indexOf(HASHES, NAMES, key)]} over {@code static
 *       final} arrays (a miss returns 0), the folded-constant hot path.
 * </ul>
 *
 * <p><b>The axis that matters: inlining.</b> A prior investigation found the {@code TagInterceptor}
 * switch wasn't being inlined / constant-propagated into its hot caller, so it ran as a real call.
 * Each form is therefore measured both ways via {@link CompilerControl}: {@code _inlined} (the
 * lookup is inlined into the measured loop) and {@code _noinline} (the lookup is a real call —
 * {@code TagInterceptor}'s actual regime). The teaching point is that the switch can look
 * competitive when inlined but loses ground when it isn't, while the StringIndex {@code Support}
 * path stays flat — so the win is largest exactly where it's needed.
 *
 * <p>The {@code _inlined} and {@code _noinline} helpers carry duplicate bodies on purpose: that's
 * the only way to pin each form's inlining decision independently.
 *
 * <p>{@code @Threads(8)}; read-only, so no store dilutes the signal. Hit keys are the interned
 * literals (the {@code ==} fast path StringIndex and the switch both get); misses are distinct and
 * never present. Run via {@code -Pjmh.includes=StringIndexSwitchBenchmark} (add {@code -prof gc} —
 * should be ~0 B/op both ways; this proves throughput, not allocation).
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Benchmark)
public class StringIndexSwitchBenchmark {
  static final String[] KEYS = {
    "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
    "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa"
  };

  /** Distinct String instances that are never present, for the miss path. */
  static final String[] MISSES = newMisses();

  static String[] newMisses() {
    String[] misses = new String[KEYS.length * 2];
    for (int i = 0; i < misses.length; ++i) {
      misses[i] = "dne-" + i;
    }
    return misses;
  }

  // StringIndex placed arrays + slot-aligned ids, pulled into static final fields so the JIT folds
  // the refs to constants (the hot path StringIndex recommends). IDS[slot] is the 1-based id;
  // empty slots stay 0, which doubles as the "not found" sentinel.
  static final int[] HASHES;
  static final String[] NAMES;
  static final int[] IDS;

  static {
    StringIndex.Data data = StringIndex.Support.create(KEYS);
    HASHES = data.hashes;
    NAMES = data.names;
    IDS = new int[HASHES.length];
    for (int i = 0; i < KEYS.length; ++i) {
      IDS[StringIndex.Support.indexOf(HASHES, NAMES, KEYS[i])] = i + 1; // 1-based; 0 = not found
    }
  }

  /** Per-thread cursors so threads don't contend on a shared index under {@code @Threads(8)}. */
  @State(Scope.Thread)
  public static class Cursor {
    int hit = 0;
    int miss = 0;

    String nextHit() {
      int i = hit + 1;
      if (i >= KEYS.length) {
        i = 0;
      }
      hit = i;
      return KEYS[i];
    }

    String nextMiss() {
      int i = miss + 1;
      if (i >= MISSES.length) {
        i = 0;
      }
      miss = i;
      return MISSES[i];
    }
  }

  @CompilerControl(CompilerControl.Mode.INLINE)
  static int switchInline(String key) {
    switch (key) {
      case "alpha":
        return 1;
      case "bravo":
        return 2;
      case "charlie":
        return 3;
      case "delta":
        return 4;
      case "echo":
        return 5;
      case "foxtrot":
        return 6;
      case "golf":
        return 7;
      case "hotel":
        return 8;
      case "india":
        return 9;
      case "juliet":
        return 10;
      case "kilo":
        return 11;
      case "lima":
        return 12;
      case "mike":
        return 13;
      case "november":
        return 14;
      case "oscar":
        return 15;
      case "papa":
        return 16;
      default:
        return 0;
    }
  }

  // Duplicate body, pinned non-inlinable -- TagInterceptor's actual call regime.
  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  static int switchNoInline(String key) {
    switch (key) {
      case "alpha":
        return 1;
      case "bravo":
        return 2;
      case "charlie":
        return 3;
      case "delta":
        return 4;
      case "echo":
        return 5;
      case "foxtrot":
        return 6;
      case "golf":
        return 7;
      case "hotel":
        return 8;
      case "india":
        return 9;
      case "juliet":
        return 10;
      case "kilo":
        return 11;
      case "lima":
        return 12;
      case "mike":
        return 13;
      case "november":
        return 14;
      case "oscar":
        return 15;
      case "papa":
        return 16;
      default:
        return 0;
    }
  }

  @CompilerControl(CompilerControl.Mode.INLINE)
  static int indexInline(String key) {
    int slot = StringIndex.Support.indexOf(HASHES, NAMES, key);
    return slot >= 0 ? IDS[slot] : 0;
  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  static int indexNoInline(String key) {
    int slot = StringIndex.Support.indexOf(HASHES, NAMES, key);
    return slot >= 0 ? IDS[slot] : 0;
  }

  @Benchmark
  public int switch_hit_inlined(Cursor cursor) {
    return switchInline(cursor.nextHit());
  }

  @Benchmark
  public int switch_miss_inlined(Cursor cursor) {
    return switchInline(cursor.nextMiss());
  }

  @Benchmark
  public int switch_hit_noinline(Cursor cursor) {
    return switchNoInline(cursor.nextHit());
  }

  @Benchmark
  public int switch_miss_noinline(Cursor cursor) {
    return switchNoInline(cursor.nextMiss());
  }

  @Benchmark
  public int stringIndex_hit_inlined(Cursor cursor) {
    return indexInline(cursor.nextHit());
  }

  @Benchmark
  public int stringIndex_miss_inlined(Cursor cursor) {
    return indexInline(cursor.nextMiss());
  }

  @Benchmark
  public int stringIndex_hit_noinline(Cursor cursor) {
    return indexNoInline(cursor.nextHit());
  }

  @Benchmark
  public int stringIndex_miss_noinline(Cursor cursor) {
    return indexNoInline(cursor.nextMiss());
  }
}
