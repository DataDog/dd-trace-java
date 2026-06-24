package datadog.trace.instrumentation.jdbc;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for the duplicate-comment guard in {@link SQLCommenter#inject} -- the {@code
 * hasDDComment} path that avoids double-commenting an already-instrumented statement.
 *
 * <p><b>What we're measuring.</b> The guard used to materialize {@code sql.substring(commentStart,
 * commentEnd)} (the comment body) just to scan it for trace-comment needles. (B) checks the comment
 * region in place via {@code SharedDBCommenter.containsTraceComment(sql, from, to)} -- no
 * substring.
 *
 * <p><b>Isolation.</b> The substring only happens when the SQL already carries a comment in the
 * checked position; for a DD comment {@code inject} then returns early. Passing {@code dbType=null}
 * skips the first-word scan (benchmarked separately for the {@code getFirstWord} change), so over
 * already-DD-commented SQL the <i>only</i> allocation left in {@code inject} is the substring (B)
 * removes. Run at {@code @Threads(8)} with {@code -prof gc}.
 *
 * <pre>
 *   ./gradlew :dd-java-agent:instrumentation:jdbc:jmh   # add -prof gc
 * </pre>
 *
 * <p><b>Results</b> (JDK 25, MacBook M-series, {@code @Threads(8)}, {@code @Fork(2)}, {@code -prof
 * gc}):
 *
 * <pre>
 *                        throughput            gc.alloc.rate.norm
 *   before (substring)   15.4M ± 3.1M ops/s    140 B/op
 *   after  (range/view)  16.8M ± 1.3M ops/s    ~0  B/op  (10^-5)
 * </pre>
 *
 * The extractCommentContent substring (140 B/op) is gone -- the in-place range scan and the
 * SubSequence view it flows through are both EA-elided. The allocation delta is exact and
 * fork-stable; that's the win. Throughput is flat-to-slightly-up but not cleanly resolved at
 * {@code @Fork(2)} (the "before" +-20% is bimodal) -- this path is dominated by the nine indexOf
 * scans (CPU the alloc-removal doesn't touch), so the win here is the allocation, a small cut that
 * compounds across comment-bearing injects, not a per-call throughput jump.
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class SQLCommenterDuplicateCommentBenchmark {

  // Already-DD-commented SQL (append style, comment at the end). First needle hits at different
  // depths: ddps first (cheap), traceparent-only (scans 8 before the match).
  static final String[] SQL = {
    "SELECT * FROM foo /*ddps='svc',dde='test',dddbs='mydb',ddh='h',dddb='n',traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-00'*/",
    "SELECT * FROM bar WHERE id = 42 /*traceparent='00-00000000000000007fffffffffffffff-000000024cb016ea-01'*/",
  };

  /** Per-thread cursor so threads don't contend on a shared index under {@code @Threads(8)}. */
  @State(Scope.Thread)
  public static class Cursor {
    int index = 0;

    String next() {
      int i = index;
      index = (i + 1) % SQL.length;
      return SQL[i];
    }
  }

  @Benchmark
  public boolean alreadyCommented(Cursor cursor) {
    // dbType=null skips the first-word scan; the DD comment makes inject return early after the
    // duplicate-comment check -- the path (B) optimizes. Returns the input sql (no new String).
    return SQLCommenter.inject(cursor.next(), "mydb", null, "h", "n", null, true) != null;
  }
}
