package datadog.trace.bootstrap.instrumentation.dbm;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark for {@link SharedDBCommenter#containsTraceComment(String, int, int)} on a statement
 * that starts with a comment that is not ours -- the prepend-mode duplicate-comment check in {@code
 * SQLCommenter.hasDDComment}.
 *
 * <p><b>What we're measuring.</b> The check looks for nine needles inside the comment region {@code
 * [from, to)}. When none is present (the common case: an ORM or app comment), every needle is
 * searched for. The cost should depend on the comment's length, not the statement's. The statement
 * length is the {@code @Param}, and the comment stays the same, so a flat line across the params
 * means the search stays inside the region; a line that grows with {@code sqlLength} means each
 * search runs past the region to the end of the statement.
 *
 * <pre>
 *   ./gradlew :dd-java-agent:agent-bootstrap:jmh -PtestJvm=17 \
 *     -Pjmh.includes=SharedDBCommenterLeadingCommentBenchmark,SharedDBCommenterBenchmark
 * </pre>
 *
 * <p>{@link SharedDBCommenterBenchmark} is the check that the fix costs nothing when the region is
 * the whole string.
 *
 * <p><b>Results</b> (JDK 17.0.7, MacBook M-series, {@code @Threads(8)}, ops/s):
 *
 * <pre>
 *   sqlLength                     200       2000      20000    whole-string region
 *   before (indexOf to end)       11.4M     1.29M     133K     105.3M ± 1.6M
 *   bounded loop only             38.9M     38.6M     38.7M     93.4M ± 2.9M
 *   after (indexOf or loop)       38.5M     39.6M     39.9M    101.5M ± 3.3M
 * </pre>
 *
 * Before, throughput fell 10x for every 10x in statement length, even though the comment was the
 * same. After, it stays flat. A plain bounded loop was slower than the {@code indexOf} intrinsic on
 * the whole-string region, so {@code Strings.regionContains} keeps {@code indexOf} when the text
 * past the region is no longer than the region.
 */
@Fork(3)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
@State(Scope.Benchmark)
public class SharedDBCommenterLeadingCommentBenchmark {

  private static final String LEADING_COMMENT = "/* app='orders-service',route='/api/v1/orders' */";

  /** Total statement length, including the leading comment. */
  @Param({"200", "2000", "20000"})
  int sqlLength;

  String sql;
  int from;
  int to;

  @Setup
  public void setup() {
    StringBuilder sb = new StringBuilder(sqlLength);
    sb.append(LEADING_COMMENT).append(" SELECT o.id, o.total FROM orders o WHERE o.id IN (");
    int id = 0;
    while (sb.length() < sqlLength - 1) {
      sb.append(id++).append(',');
    }
    sb.setLength(sqlLength - 1);
    sb.append(')');
    sql = sb.toString();
    from = sql.indexOf("/*") + 2;
    to = sql.indexOf("*/");
  }

  @Benchmark
  public boolean leadingNonDDComment() {
    return SharedDBCommenter.containsTraceComment(sql, from, to);
  }
}
