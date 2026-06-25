package datadog.trace.instrumentation.jdbc;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for {@link SQLCommenter#getFirstWord(String)} -- the per-{@code inject} first-word
 * scan.
 *
 * <p><b>What we're measuring.</b> {@code getFirstWord} used to return {@code sql.substring(b, e)} --
 * a fresh {@code String} (with its own backing array) allocated on every {@code inject} call, just
 * to {@code startsWith}/{@code equalsIgnoreCase} it. It now returns a zero-copy {@code SubSequence}
 * view. The question is empirical: does escape analysis elide the view in the transient consumption
 * (→ 0 B/op), while the old {@code substring} always allocated?
 *
 * <p><b>Honest EA measurement.</b> The view is consumed exactly as {@code inject} consumes it -- a
 * boolean decision ({@code startsWith("{")}) -- and the benchmark returns that boolean. It does NOT
 * return/Blackhole the view itself, which would force it to escape and fake away the very EA win
 * under test. The chained {@code getFirstWord(sql).startsWith("{")} (no typed local) also lets one
 * source compile both before (String.startsWith) and after (SubSequence.startsWith), so before/after
 * is a clean toggle of the production method.
 *
 * <p>Run at {@code @Threads(8)} so the allocation delta surfaces as throughput; {@code -prof gc}
 * (gc.alloc.rate.norm) is the headline mechanism and is fork-stable.
 *
 * <pre>
 *   ./gradlew :dd-java-agent:instrumentation:jdbc:jmh   # add -prof gc
 * </pre>
 *
 * <p><b>Results</b> (JDK 17, MacBook M-series, {@code @Threads(8)}, {@code @Fork(5)}, {@code -prof
 * gc}):
 *
 * <pre>
 *                        throughput            gc.alloc.rate.norm
 *   before (substring)   258.1M ± 21.0M ops/s   48 B/op
 *   after  (SubSequence) 508.0M ± 21.6M ops/s   ~0 B/op  (10^-4)
 * </pre>
 *
 * Escape analysis fully elides the view in the transient consumption (it never escapes the
 * decision), so the per-call 48 B/op of the old {@code substring} (a String + its backing array)
 * drops to ~0 and throughput rises ~2x at {@code @Threads(8)} — the allocation win surfacing as
 * throughput. At {@code @Fork(5)} the error tightens (the earlier {@code @Fork(2)} spread was
 * bimodal JIT, not signal); the allocation delta is exact and the throughput gap clears it.
 */
@Fork(5)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class SQLCommenterGetFirstWordBenchmark {

  // Representative first-word shapes: plain keywords, a stored-proc brace, a CALL, leading space.
  static final String[] SQL = {
    "SELECT * FROM foo WHERE id = 42",
    "{call dogshelterProc(?, ?)}",
    "CALL dogshelterProc(?, ?)",
    "UPDATE accounts SET balance = balance - 100 WHERE id = 42",
    "  INSERT INTO logs VALUES (?)",
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
  public boolean firstWordCheck(Cursor cursor) {
    // Mirrors inject(): take the first word, make a boolean decision, discard it.
    return SQLCommenter.getFirstWord(cursor.next()).startsWith("{");
  }
}
