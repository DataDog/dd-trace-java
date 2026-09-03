package datadog.trace.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Membership over Hibernate's <b>two-element</b> scope-only method set — the decision APMLP-1665
 * actually poses, measured at the cardinality the call site actually has.
 *
 * <p>{@code SessionMethodUtils.SCOPE_ONLY_METHODS} holds two names and is checked from {@code
 * SessionInstrumentation}'s per-intercepted-method advice. {@link ImmutableSetBenchmark} measures
 * twelve entries, where a hash probe's fixed cost is amortised against a linear scan's growing one;
 * at n=2 that trade no longer holds, so its conclusions do not transfer here.
 *
 * <p>Three strategies, deliberately narrow — the incumbent, the proposal, and the simplest thing
 * that could work:
 *
 * <ul>
 *   <li>{@code hashSet} — what {@code SessionMethodUtils} does today. Note {@code
 *       HashSet(Collection)} sizes its table to {@code max(size/0.75 + 1, 16)}, so at n=2 it is
 *       <b>8x oversized</b>: a miss lands in an empty bucket ~87% of the time and returns without
 *       calling {@code equals} at all.
 *   <li>{@code embedded} — {@link StringIndex.EmbeddingSupport#indexOf} over {@code static final}
 *       arrays, what APMLP-1665 proposes. {@link StringIndex.EmbeddingSupport#capacityFor(int)}
 *       gives 4 slots for 2 names at the default 0.5 load factor, so this table is <b>50%
 *       occupied</b> — denser than the HashSet it replaces, and misses pay a hash compare
 *       correspondingly more often.
 *   <li>{@code orChain} — two inlined {@link String#equals} calls against literals. No table, no
 *       hashing, no array load, nothing to dereference.
 * </ul>
 *
 * <p><b>Hypothesis under test:</b> that {@code orChain} beats both on throughput, on hits and
 * especially on misses. The n=2 run that preceded this one did <i>not</i> show that for misses
 * ({@code orChain} 2530 vs {@code hashSet} 2563 M ops/s, a statistical tie) — but that run left
 * dispatch monomorphic, which flatters {@code hashSet} alone. This run corrects that.
 *
 * <p><b>Keys are constant-pool strings on both paths</b>, which is what the advice is handed via
 * {@code @Advice.Origin("#m")}. That matters in both directions: it gives the hashed forms a free
 * cached {@code hashCode} and gives {@code orChain} a free identity check inside {@code equals}.
 * The key population is not a sample — {@link #MEMBERS} and {@link #NON_MEMBERS} together are
 * exactly the {@code namedOneOf(...)} list {@code SessionMethodAdvice} is applied to, so they are
 * every name this call site can ever see. Note {@code saveOrUpdate} is 12 chars, the same length as
 * {@code internalLoad}, so at least one miss cannot be rejected on length alone.
 *
 * <p><b>Config rationale.</b> {@code @Fork(10)} because arms in this family are bimodal across
 * forks — C2 compiles the same code two or more ways and each fork locks one in at warmup, so a
 * mean over too few forks is a mode-mix rather than a measurement. Warmup is raised to 6 iterations
 * (30s) after a fork in the previous run was still recompiling <i>during</i> its measurement
 * iterations (964 -&gt; 2203 -&gt; 3179 M ops/s). Iteration time is 5s, as in {@code
 * LettuceCommandMatchingBenchmark}, to keep 10 forks affordable.
 *
 * <pre>
 *   ./gradlew :internal-api:jmh -Pjmh.includes=ScopeOnlyMembershipBenchmark
 * </pre>
 */
@Fork(10) // bimodal arms need a mode histogram, not a mean over 5
@Warmup(iterations = 6, time = 5)
@Measurement(iterations = 5, time = 5)
@Threads(8)
@State(Scope.Benchmark)
public class ScopeOnlyMembershipBenchmark {

  /** The real scope-only set — n=2. */
  static final String[] MEMBERS = {"immediateLoad", "internalLoad"};

  /**
   * The other names {@code SessionMethodAdvice} is applied to: the complete miss population for
   * this call site, every one a constant-pool literal.
   */
  static final String[] NON_MEMBERS = {
    "save", "replicate", "saveOrUpdate", "update", "merge", "persist",
    "lock", "refresh", "insert", "delete", "iterate", "get"
  };

  // Placed arrays pulled into static final fields so the JIT folds the refs to constants.
  static final int[] SI_HASHES;
  static final String[] SI_NAMES;

  static {
    StringIndex.Data data = StringIndex.EmbeddingSupport.create(MEMBERS);
    SI_HASHES = data.hashes;
    SI_NAMES = data.names;
  }

  Set<String> hashSet;

  @Setup(Level.Trial)
  public void setUp() {
    hashSet = new HashSet<>(Arrays.asList(MEMBERS));
    polluteHashDispatch();
  }

  /**
   * Drives {@code HashMap}'s internal {@code key.hashCode()}/{@code equals()} call sites — shared
   * JVM-wide by every hash-based structure in the process — megamorphic before anything is
   * measured, which is how they look in any real application. Without this, an isolated benchmark
   * that only ever looks up {@code String} leaves them artificially monomorphic and understates
   * {@code hashSet}'s real dispatch cost. {@code orChain} and {@code embedded} call {@code
   * String.equals}/{@code String.hashCode} on a {@code final} class, so neither is affected.
   *
   * <p>Technique from {@code BenchmarkUtils#polluteHashDispatch} on {@code
   * dougqh/stringindex-immutable-set-benchmark}; reimplemented here rather than depended on, since
   * that helper is not on master.
   */
  private static void polluteHashDispatch() {
    final Object[] decoys = {"decoy", 1, 1L, 1.0d, Boolean.TRUE, new Object()};
    final Set<Object> scratch = new HashSet<>();
    for (final Object key : decoys) {
      scratch.add(key);
      scratch.contains(key);
    }
    final Set<Object> immutable = CollectionUtils.tryMakeImmutableSet(Arrays.asList(decoys));
    for (final Object key : decoys) {
      immutable.contains(key);
    }
  }

  /** Per-thread cursors so each reader thread cycles keys independently. */
  @State(Scope.Thread)
  public static class Cursor {
    int hitIndex = 0;
    int missIndex = 0;

    String nextHit() {
      int i = hitIndex + 1;
      if (i >= MEMBERS.length) {
        i = 0;
      }
      hitIndex = i;
      return MEMBERS[i];
    }

    String nextMiss() {
      int i = missIndex + 1;
      if (i >= NON_MEMBERS.length) {
        i = 0;
      }
      missIndex = i;
      return NON_MEMBERS[i];
    }
  }

  /** The candidate: two inlined equals against literals. */
  static boolean orChain(final String m) {
    return "immediateLoad".equals(m) || "internalLoad".equals(m);
  }

  @Benchmark
  public boolean hashSet_hit(Cursor cursor) {
    return hashSet.contains(cursor.nextHit());
  }

  @Benchmark
  public boolean hashSet_miss(Cursor cursor) {
    return hashSet.contains(cursor.nextMiss());
  }

  @Benchmark
  public boolean embedded_hit(Cursor cursor) {
    return StringIndex.EmbeddingSupport.indexOf(SI_HASHES, SI_NAMES, cursor.nextHit()) >= 0;
  }

  @Benchmark
  public boolean embedded_miss(Cursor cursor) {
    return StringIndex.EmbeddingSupport.indexOf(SI_HASHES, SI_NAMES, cursor.nextMiss()) >= 0;
  }

  @Benchmark
  public boolean orChain_hit(Cursor cursor) {
    return orChain(cursor.nextHit());
  }

  @Benchmark
  public boolean orChain_miss(Cursor cursor) {
    return orChain(cursor.nextMiss());
  }
}
