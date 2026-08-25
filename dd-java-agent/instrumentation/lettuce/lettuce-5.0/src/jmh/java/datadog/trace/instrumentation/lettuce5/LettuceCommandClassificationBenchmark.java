package datadog.trace.instrumentation.lettuce5;

import datadog.trace.util.StringIndex;
import io.lettuce.core.protocol.CommandType;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Membership cost of the two command-name sets in {@link LettuceInstrumentationUtil}, comparing
 * three structures: {@code HashSet} (in use today), a {@link StringIndex} instance, and {@link
 * StringIndex.EmbeddingSupport} over {@code static final} arrays.
 *
 * <p>Each {@code perCommand} arm performs both lookups, matching what one Redis command does:
 * {@code agentCrashingCommands} via {@code LettuceClientDecorator.onCommand}, and {@code
 * nonInstrumentingCommands} via {@code expectsResponse}. Ordinary commands ({@code GET}, {@code
 * SET}, {@code HGETALL}, {@code INCR}) are in neither set, so the {@code _miss} arms cover the
 * common path and the {@code _hit} arms the administrative commands the sets contain.
 *
 * <p>All three candidates are declared {@code static final}, as production declares its sets, so
 * the comparison reflects the structures rather than static-versus-instance field access. Keys are
 * produced the way {@code getCommandName} produces them ({@code getType().toString().trim()}),
 * which keeps the interned-constant identity fast path in {@code String.equals} intact.
 *
 * <p>{@code nonInstrumentingCommands} declares four entries, of which two are reachable through
 * lettuce's built-in commands: {@code CommandType} (174 constants in lettuce 5.0.0) defines {@code
 * SHUTDOWN} and {@code DEBUG} but neither {@code OOM} nor {@code SEGFAULT}, and {@code SEGFAULT} is
 * a {@code CommandKeyword} rather than a command type. {@code getType()} returns a {@code
 * ProtocolKeyword} though, and a custom implementation may report either name, so these entries are
 * unreachable via {@code CommandType} rather than unreachable outright. Both arrays are benchmarked
 * as declared.
 *
 * <p>Results (JDK 17.0.18, Apple M4 Max, {@code @Fork(3)}, {@code @Threads(1)}; ns/op, lower is
 * better), with allocation from a separate {@code -Pjmh.profilers=gc} run:
 *
 * <pre>{@code
 * Benchmark                                     ns/op         alloc B/op
 * hashSet_perCommand_miss                 1.715 +- 0.071          ~0
 * stringIndexEmbedded_perCommand_miss     1.881 +- 0.295          ~0
 * stringIndexInstance_perCommand_miss     2.687 +- 0.151          ~0
 * hashSet_perCommand_hit                  1.938 +- 0.158
 * stringIndexEmbedded_perCommand_hit      1.890 +- 0.117
 * stringIndexInstance_perCommand_hit      2.286 +- 0.100
 * }</pre>
 *
 * <p>Miss-path scores over six runs, since a single run understates the run-to-run spread:
 *
 * <pre>{@code
 *                       run scores                              mean    spread
 * hashSet             1.708 1.706 1.736 1.707 1.744 1.715      1.719     0.038
 * stringIndexEmbedded 1.699 1.668 1.698 1.796 1.680 1.881      1.737     0.213
 * stringIndexInstance 2.667 2.665 2.635 2.682 2.773 2.687      2.685     0.138
 * }</pre>
 *
 * <ul>
 *   <li>{@code stringIndexEmbedded} and {@code hashSet} are indistinguishable on the miss path:
 *       means within 1% over six runs, confidence intervals overlapping in every one. {@code
 *       stringIndexEmbedded} is the less stable of the two, spanning 1.668-1.881 against
 *       1.706-1.744, and its noisiest runs carry a within-run stdev around 0.176 against 0.042.
 *   <li>{@code stringIndexInstance} is consistently the slowest on the miss path, roughly 56% above
 *       {@code hashSet}, with confidence intervals that never overlap it.
 *   <li>On the hit path {@code stringIndexEmbedded} runs about 6% under {@code hashSet} and did so
 *       in every run, though the intervals overlap within each one. Hits are the rare case here:
 *       ordinary commands miss both sets.
 *   <li>{@code gc.alloc.rate.norm} sits at the 10^-6 B/op measurement floor for every arm and
 *       {@code gc.count} is zero, so none of the three structures allocates per lookup.
 * </ul>
 */
// Modest defaults so a full sweep of the arms runs in a few minutes. Raise forks for a marginal
// result: ./gradlew ...:jmh -Pjmh.includes=LettuceCommandClassificationBenchmark -Pjmh.forks=5
@Fork(3)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class LettuceCommandClassificationBenchmark {

  // Production's actual arrays -- LettuceInstrumentationUtil:14-21.
  static final String[] NON_INSTRUMENTING_WORDS =
      new String[] {"SHUTDOWN", "DEBUG", "OOM", "SEGFAULT"};
  static final String[] AGENT_CRASHING_WORDS =
      new String[] {"CLIENT", "CLUSTER", "COMMAND", "CONFIG", "DEBUG", "SCRIPT"};

  // --- candidate 1: HashSet, as production declares it today ---
  static final Set<String> NI_HASH_SET = new HashSet<>(Arrays.asList(NON_INSTRUMENTING_WORDS));
  static final Set<String> AC_HASH_SET = new HashSet<>(Arrays.asList(AGENT_CRASHING_WORDS));

  // --- candidate 2: StringIndex instance wrapper ---
  static final StringIndex NI_INDEX = StringIndex.of(NON_INSTRUMENTING_WORDS);
  static final StringIndex AC_INDEX = StringIndex.of(AGENT_CRASHING_WORDS);

  // --- candidate 3: EmbeddingSupport over static final arrays ---
  static final int[] NI_HASHES;
  static final String[] NI_NAMES;
  static final int[] AC_HASHES;
  static final String[] AC_NAMES;

  static {
    StringIndex.Data ni = StringIndex.EmbeddingSupport.create(NON_INSTRUMENTING_WORDS);
    NI_HASHES = ni.hashes;
    NI_NAMES = ni.names;
    StringIndex.Data ac = StringIndex.EmbeddingSupport.create(AGENT_CRASHING_WORDS);
    AC_HASHES = ac.hashes;
    AC_NAMES = ac.names;
  }

  /**
   * Keys built as {@code LettuceInstrumentationUtil.getCommandName} builds them, preserving the
   * interned-constant identity fast path inside {@code String.equals}.
   */
  static String[] keys(final CommandType... types) {
    final String[] out = new String[types.length];
    for (int i = 0; i < types.length; i++) {
      out[i] = types[i].toString().trim();
    }
    return out;
  }

  /** Ordinary commands, absent from both sets. */
  static final String[] MISS_KEYS =
      keys(CommandType.GET, CommandType.SET, CommandType.HGETALL, CommandType.INCR);

  /** Administrative commands, present in one or both sets. */
  static final String[] HIT_KEYS =
      keys(CommandType.DEBUG, CommandType.SHUTDOWN, CommandType.COMMAND, CommandType.CONFIG);

  /** Per-thread cursor so keys cycle rather than repeating one value. */
  @State(Scope.Thread)
  public static class Cursor {
    int missIndex = 0;
    int hitIndex = 0;

    String nextMiss() {
      int i = missIndex + 1;
      if (i >= MISS_KEYS.length) {
        i = 0;
      }
      missIndex = i;
      return MISS_KEYS[i];
    }

    String nextHit() {
      int i = hitIndex + 1;
      if (i >= HIT_KEYS.length) {
        i = 0;
      }
      hitIndex = i;
      return HIT_KEYS[i];
    }
  }

  // --- per-command pair: both lookups, as one Redis command performs them ---
  // Non-short-circuiting & so both lookups always run.

  @Benchmark
  public boolean hashSet_perCommand_miss(final Cursor cursor) {
    final String key = cursor.nextMiss();
    return AC_HASH_SET.contains(key) & NI_HASH_SET.contains(key);
  }

  @Benchmark
  public boolean stringIndexInstance_perCommand_miss(final Cursor cursor) {
    final String key = cursor.nextMiss();
    return AC_INDEX.contains(key) & NI_INDEX.contains(key);
  }

  @Benchmark
  public boolean stringIndexEmbedded_perCommand_miss(final Cursor cursor) {
    final String key = cursor.nextMiss();
    return (StringIndex.EmbeddingSupport.indexOf(AC_HASHES, AC_NAMES, key) >= 0)
        & (StringIndex.EmbeddingSupport.indexOf(NI_HASHES, NI_NAMES, key) >= 0);
  }

  @Benchmark
  public boolean hashSet_perCommand_hit(final Cursor cursor) {
    final String key = cursor.nextHit();
    return AC_HASH_SET.contains(key) & NI_HASH_SET.contains(key);
  }

  @Benchmark
  public boolean stringIndexInstance_perCommand_hit(final Cursor cursor) {
    final String key = cursor.nextHit();
    return AC_INDEX.contains(key) & NI_INDEX.contains(key);
  }

  @Benchmark
  public boolean stringIndexEmbedded_perCommand_hit(final Cursor cursor) {
    final String key = cursor.nextHit();
    return (StringIndex.EmbeddingSupport.indexOf(AC_HASHES, AC_NAMES, key) >= 0)
        & (StringIndex.EmbeddingSupport.indexOf(NI_HASHES, NI_NAMES, key) >= 0);
  }
}
