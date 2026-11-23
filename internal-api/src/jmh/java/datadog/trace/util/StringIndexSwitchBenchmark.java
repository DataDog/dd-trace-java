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
 *   <li>{@code stringIndex} — {@code IDS[EmbeddingSupport.indexOf(HASHES, NAMES, key)]} over {@code
 *       static final} arrays (a miss returns 0), the folded-constant hot path.
 * </ul>
 *
 * <p><b>What this measures: two axes.</b> A prior investigation found the {@code TagInterceptor}
 * switch wasn't being inlined / specialized into its hot caller. So each form is measured across
 * (a) inlining — {@code _inlined} vs {@code _noinline} (a real call, {@code TagInterceptor}'s
 * actual regime) via {@link CompilerControl} — and (b) key shape — a constant key vs a runtime,
 * varied key. The results (below) land the teaching point: the dominant axis is
 * <i>key-constancy</i>, not inlining. At steady state the inline-vs-not gap is small for both
 * forms; what sinks the switch is a runtime, varied key (it can't specialize), while the
 * StringIndex {@code EmbeddingSupport} path stays flat across both axes — so the win is largest
 * exactly where {@code TagInterceptor} lives.
 *
 * <p>The {@code _inlined} and {@code _noinline} helpers carry duplicate bodies on purpose: that's
 * the only way to pin each form's inlining decision independently.
 *
 * <p>{@code @Threads(8)}; read-only, so no store dilutes the signal. Hit keys are the interned
 * literals (the {@code ==} fast path StringIndex and the switch both get); misses are distinct and
 * never present. Run via {@code -Pjmh.includes=StringIndexSwitchBenchmark} (add {@code -prof gc} —
 * should be ~0 B/op both ways; this proves throughput, not allocation).
 *
 * <p>JDK 17 results (Apple M1, quiet machine, {@code @Fork(5)}, {@code @Threads(8)}; M ops/s,
 * ±1–5%):
 *
 * <pre>{@code
 * key             switch (inl / noinl)   stringIndex (inl / noinl)
 * const            2778 / 2769            2047 / 2035
 * hit  (runtime)   1161 / 1166            2147 / 2152
 * miss             2083 / 2050            2546 / 2539
 * }</pre>
 *
 * <p>Two takeaways:
 *
 * <ul>
 *   <li>The string switch only <i>matches</i> StringIndex in the <b>constant-key</b> corner
 *       (~2.7B): there the JIT specializes the switch to the single known key — and the {@code
 *       const} arms show it does so even across a {@code DONT_INLINE} boundary (profile-driven, not
 *       const-prop-through-inline). Production tags are runtime-varied, so that corner never
 *       occurs.
 *   <li>In the realistic regime — a <b>runtime, varied hit key</b>, exactly {@code TagInterceptor}
 *       — the switch falls to ~1.16B while StringIndex holds ~2.15B (<b>~1.85x</b>). StringIndex is
 *       flat (~2.0–2.5B) across inline/not-inline <i>and</i> key shape: its throughput doesn't
 *       depend on the JIT's inlining decisions, which is the whole point. (Misses short-circuit for
 *       both; StringIndex still ~1.2x.)
 * </ul>
 *
 * <p>So the {@code const} arm is the control: it exposes the switch's "fast" as a single-key
 * specialization artifact — drop the constant and the switch is ~half StringIndex's throughput.
 */
@Fork(5) // matches the documented @Fork(5) numbers; the switch's const-key arm is profile-bimodal
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Benchmark)
public class StringIndexSwitchBenchmark {
  static final String[] KEYS = {
    "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
    "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa"
  };

  // A compile-time-constant hit key. javac inlines it, so the JIT can constant-propagate it into an
  // inlined switch and fold the whole switch away -- the switch's theoretical ceiling. The const_*
  // arms pair this with INLINE vs DONT_INLINE to show that ceiling only materializes when the call
  // ALSO inlines: across a DONT_INLINE boundary the constant can't propagate in, so the switch runs
  // in full. TagInterceptor's real regime is a runtime tag through a non-inlined call -- neither
  // holds -- which is why StringIndex wins where it counts.
  static final String CONST_KEY = "mike";

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
    StringIndex.Data data = StringIndex.EmbeddingSupport.create(KEYS);
    HASHES = data.hashes;
    NAMES = data.names;
    IDS = new int[HASHES.length];
    for (int i = 0; i < KEYS.length; ++i) {
      IDS[StringIndex.EmbeddingSupport.indexOf(HASHES, NAMES, KEYS[i])] =
          i + 1; // 1-based; 0 = not found
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
    int slot = StringIndex.EmbeddingSupport.indexOf(HASHES, NAMES, key);
    return slot >= 0 ? IDS[slot] : 0;
  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  static int indexNoInline(String key) {
    int slot = StringIndex.EmbeddingSupport.indexOf(HASHES, NAMES, key);
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

  // --- constant key: the switch's best case (const-propagated). Inlined -> folds away; not-inlined
  // -> the constant can't cross the boundary, so the switch runs in full. ---

  @Benchmark
  public int switch_const_inlined() {
    return switchInline(CONST_KEY);
  }

  @Benchmark
  public int switch_const_noinline() {
    return switchNoInline(CONST_KEY);
  }

  @Benchmark
  public int stringIndex_const_inlined() {
    return indexInline(CONST_KEY);
  }

  @Benchmark
  public int stringIndex_const_noinline() {
    return indexNoInline(CONST_KEY);
  }
}
