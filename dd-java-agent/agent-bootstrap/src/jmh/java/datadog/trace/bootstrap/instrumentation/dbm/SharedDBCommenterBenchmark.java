package datadog.trace.bootstrap.instrumentation.dbm;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for {@link SharedDBCommenter#containsTraceComment(String)} — the per-query check run
 * during inject (via {@code hasDDComment}) to avoid double-commenting an already-tagged statement.
 *
 * <p><b>What we're measuring.</b> {@code containsTraceComment} currently does {@code
 * commentContent.contains(KEY + "=")} for nine keys. The keys are {@code static final} but assigned
 * via {@code encode(...)}, so they are <i>not</i> compile-time constants — each {@code KEY + "="}
 * is a fresh {@code StringBuilder} concat on every call. A non-matching comment runs all nine
 * checks = nine throwaway Strings per call. The proposed fix precomputes nine {@code KEY_EQ}
 * constants once.
 *
 * <p><b>How we make the win visible (our usual approach).</b> Run at {@code @Threads(8)} so the
 * allocation churn manifests as a <i>throughput</i> delta — GC is a shared-heap tax, so a
 * single-threaded run (cheap TLAB bumps) hides it, while concurrent allocation across threads
 * drives GC pauses that every thread pays. Read the ops/s delta as the headline win. Corroborate
 * the mechanism with the GC profiler: {@code -prof gc} → {@code gc.alloc.rate.norm} (B/op) should
 * drop by ~nine small Strings per call on the non-matching path.
 *
 * <p><b>Protocol.</b> Run this on the current code (baseline), then after the {@code
 * KEY_EQ}-constant fix, and compare. The input mix is mostly non-DD comments (the common case —
 * they run all nine checks, the exact all-nine-concats path the fix removes); the DD comment
 * short-circuits on the first check.
 *
 * <pre>
 *   # agent-bootstrap has no -Pjmh.includes wiring yet (a generalization is in flight), so for now
 *   # either run the whole module (only a handful of benchmarks) ...
 *   ./gradlew :dd-java-agent:agent-bootstrap:jmh
 *   # ... or hack a temporary filter into agent-bootstrap/build.gradle: jmh { includes = ['SharedDBCommenter.*'] }
 *   # add -prof gc (gc.alloc.rate.norm) to corroborate the allocation delta.
 * </pre>
 *
 * <p><b>Results</b> (JDK 17, MacBook M-series, {@code @Threads(8)}, {@code @Fork(5)}, {@code -prof
 * gc}):
 *
 * <pre>
 *                    throughput            gc.alloc.rate.norm
 *   before (concat)  33.5M ± 2.0M ops/s    156 B/op
 *   after  (*_EQ)    62.1M ± 3.8M ops/s    ~0  B/op  (10^-5)
 * </pre>
 *
 * Removing the per-call concatenation drops allocation to ~0 and lifts throughput ~1.9x at
 * {@code @Threads(8)} — the allocation win surfacing as throughput, exactly as intended; {@code
 * -prof gc} confirms the mechanism (156 -> 0 B/op).
 */
@Fork(5)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class SharedDBCommenterBenchmark {

  // Inner comment content (the surrounding "/*" "*/" already stripped by extractCommentContent),
  // as a realistic mix: most queries carry a non-DD comment (or none); some already have ours.
  static final String[] COMMENT_CONTENTS = {
    "app generated comment", // non-DD -> all 9 contains checks (9 concats)
    "route='/api/v1/users',batch=true", // non-DD
    "framework='hibernate',layer='orm'", // non-DD
    "ddps='web',dddbs='orders',traceparent='00-abc-def-01'", // DD -> short-circuits on 1st check
  };

  /** Per-thread cursor so threads don't contend on a shared index under {@code @Threads(8)}. */
  @State(Scope.Thread)
  public static class Cursor {
    int index = 0;

    String next() {
      int i = index;
      index = (i + 1) % COMMENT_CONTENTS.length;
      return COMMENT_CONTENTS[i];
    }
  }

  @Benchmark
  public boolean containsTraceComment(Cursor cursor) {
    return SharedDBCommenter.containsTraceComment(cursor.next());
  }
}
