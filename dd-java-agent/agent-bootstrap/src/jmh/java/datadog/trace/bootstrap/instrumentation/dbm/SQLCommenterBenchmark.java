package datadog.trace.bootstrap.instrumentation.dbm;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks the trace comment detection and first-word extraction optimizations in SQLCommenter.
 *
 * <p>Compares:
 *
 * <ul>
 *   <li>Baseline: substring allocation + String.contains for trace comment detection
 *   <li>Optimized: range-based regionMatches with zero allocation
 *   <li>Baseline: getFirstWord substring + startsWith/equalsIgnoreCase
 *   <li>Optimized: firstWordStartsWith/firstWordEqualsIgnoreCase via regionMatches
 * </ul>
 *
 * <p>Run with:
 *
 * <pre>
 *   ./gradlew :dd-java-agent:agent-bootstrap:jmhJar
 *   java -jar dd-java-agent/agent-bootstrap/build/libs/agent-bootstrap-*-jmh.jar SQLCommenterBenchmark
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class SQLCommenterBenchmark {

  // Realistic SQL with a prepended DBM comment (the common case for hasDDComment check)
  private static final String SQL_WITH_COMMENT =
      "/*ddps='myservice',dddbs='mydb',ddh='db-host.example.com',dddb='production_db',"
          + "dde='prod',ddpv='1.2.3'*/ SELECT u.id, u.name, u.email FROM users u "
          + "WHERE u.active = ? AND u.created_at > ? ORDER BY u.name";

  // SQL without any comment (the common case for normal queries)
  private static final String SQL_NO_COMMENT =
      "SELECT u.id, u.name, u.email FROM users u "
          + "WHERE u.active = ? AND u.created_at > ? ORDER BY u.name";

  // SQL with appended comment (MySQL/CALL style)
  private static final String SQL_APPENDED_COMMENT =
      "CALL get_user_data(?, ?) /*ddps='myservice',dddbs='mydb',ddh='db-host.example.com',"
          + "dddb='production_db',dde='prod',ddpv='1.2.3'*/";

  // Short SQL for first-word extraction benchmarks
  private static final String SQL_SELECT = "SELECT * FROM users WHERE id = ?";
  private static final String SQL_CALL = "CALL get_user_data(?, ?)";
  private static final String SQL_BRACE = "{ call get_user_data(?, ?) }";

  @Param({"prepended", "appended", "none"})
  String commentStyle;

  String sql;
  boolean appendComment;

  // Pre-computed indices for the optimized range-based check
  int commentStartIdx;
  int commentEndIdx;
  String commentContent; // Pre-extracted for baseline comparison

  @Setup
  public void setup() {
    switch (commentStyle) {
      case "prepended":
        sql = SQL_WITH_COMMENT;
        appendComment = false;
        break;
      case "appended":
        sql = SQL_APPENDED_COMMENT;
        appendComment = true;
        break;
      case "none":
      default:
        sql = SQL_NO_COMMENT;
        appendComment = false;
        break;
    }

    // Pre-compute the comment bounds for the range-based benchmark
    if (appendComment) {
      commentStartIdx = sql.lastIndexOf("/*");
      commentEndIdx = sql.lastIndexOf("*/");
    } else {
      commentStartIdx = sql.indexOf("/*");
      commentEndIdx = sql.indexOf("*/");
    }

    // Pre-extract the comment content for the baseline benchmark
    if (commentStartIdx != -1 && commentEndIdx != -1 && commentEndIdx > commentStartIdx) {
      commentContent = sql.substring(commentStartIdx + 2, commentEndIdx);
    } else {
      commentContent = "";
    }
  }

  // --- containsTraceComment benchmarks ---

  /**
   * Baseline: extract substring then check with String.contains. This is what the old code did via
   * extractCommentContent() + containsTraceComment(String).
   */
  @Benchmark
  @Threads(1)
  public boolean containsTraceComment_baseline_substring_1T() {
    if (commentStartIdx == -1 || commentEndIdx == -1 || commentEndIdx <= commentStartIdx) {
      return false;
    }
    // Allocates a substring — the old extractCommentContent() behavior
    String extracted = sql.substring(commentStartIdx + 2, commentEndIdx);
    return SharedDBCommenter.containsTraceComment(extracted);
  }

  /**
   * Optimized: range-based check with regionMatches, zero allocation. This is what the new code
   * does via containsTraceComment(String, int, int).
   */
  @Benchmark
  @Threads(1)
  public boolean containsTraceComment_optimized_range_1T() {
    if (commentStartIdx == -1 || commentEndIdx == -1 || commentEndIdx <= commentStartIdx) {
      return false;
    }
    return SharedDBCommenter.containsTraceComment(sql, commentStartIdx + 2, commentEndIdx);
  }

  /** Multi-threaded baseline — exposes GC pressure from substring allocation under contention. */
  @Benchmark
  @Threads(8)
  public boolean containsTraceComment_baseline_substring_8T() {
    if (commentStartIdx == -1 || commentEndIdx == -1 || commentEndIdx <= commentStartIdx) {
      return false;
    }
    String extracted = sql.substring(commentStartIdx + 2, commentEndIdx);
    return SharedDBCommenter.containsTraceComment(extracted);
  }

  /** Multi-threaded optimized — no allocation, no GC pressure. */
  @Benchmark
  @Threads(8)
  public boolean containsTraceComment_optimized_range_8T() {
    if (commentStartIdx == -1 || commentEndIdx == -1 || commentEndIdx <= commentStartIdx) {
      return false;
    }
    return SharedDBCommenter.containsTraceComment(sql, commentStartIdx + 2, commentEndIdx);
  }

  // --- firstWord benchmarks ---

  /**
   * Baseline: allocate substring via getFirstWord, then call startsWith. This is what the old
   * inject() code did.
   */
  @Benchmark
  @Threads(1)
  public boolean firstWord_baseline_substring_1T() {
    String firstWord = getFirstWord(sql);
    return firstWord.startsWith("{");
  }

  /** Optimized: regionMatches-based check, zero allocation. */
  @Benchmark
  @Threads(1)
  public boolean firstWord_optimized_regionMatches_1T() {
    return firstWordStartsWith(sql, "{");
  }

  /** Multi-threaded baseline — substring allocation under contention. */
  @Benchmark
  @Threads(8)
  public boolean firstWord_baseline_substring_8T() {
    String firstWord = getFirstWord(sql);
    return firstWord.startsWith("{");
  }

  /** Multi-threaded optimized — zero allocation. */
  @Benchmark
  @Threads(8)
  public boolean firstWord_optimized_regionMatches_8T() {
    return firstWordStartsWith(sql, "{");
  }

  // --- Inlined helper methods (mirror the implementations for fair comparison) ---

  /** Original getFirstWord — allocates a substring. */
  private static String getFirstWord(String sql) {
    int beginIndex = 0;
    while (beginIndex < sql.length() && Character.isWhitespace(sql.charAt(beginIndex))) {
      beginIndex++;
    }
    int endIndex = beginIndex;
    while (endIndex < sql.length() && !Character.isWhitespace(sql.charAt(endIndex))) {
      endIndex++;
    }
    return sql.substring(beginIndex, endIndex);
  }

  /** Optimized firstWordStartsWith — zero allocation via regionMatches. */
  private static boolean firstWordStartsWith(String sql, String prefix) {
    int beginIndex = 0;
    int len = sql.length();
    while (beginIndex < len && Character.isWhitespace(sql.charAt(beginIndex))) {
      beginIndex++;
    }
    return sql.regionMatches(beginIndex, prefix, 0, prefix.length());
  }
}
