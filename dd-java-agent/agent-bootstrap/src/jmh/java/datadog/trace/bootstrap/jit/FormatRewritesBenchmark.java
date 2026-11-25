package datadog.trace.bootstrap.jit;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * {@code String.format} against the helper that replaces it, at a warm call site (one slot per
 * benchmark, compiled during setup). Arguments come from fields and are boxed at the call, as at a
 * real call site.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(3)
public class FormatRewritesBenchmark {
  static final String PAIR = "%s-%d";
  static final String LOGIN = "user %s logged in from %s";
  static final String ITEMS = "%d items";
  static final String LINE = "processed %d of %d%n";
  static final String UNSUPPORTED = "%.2f ms";

  String user = "alice";
  String address = "10.0.0.1";
  int small = 42;
  int large = 123456;
  long total = 9876543210L;
  double millis = 3.14159;

  final FormatSite[] sites = new FormatSite[8];

  @Setup
  public void warmSites() {
    for (int i = 0; i < 2 * FormatSite.COMPILE_THRESHOLD; i++) {
      pair_helper();
      login_helper();
      items_helper();
      line_helper();
      unsupported_helper();
      items_unslotted_helper();
      items_locale_helper();
    }
    if (null == sites[0].compiled || null == sites[2].compiled || !sites[4].disabled) {
      throw new IllegalStateException("sites did not warm up as expected");
    }
  }

  @Benchmark
  public String pair_jdk() {
    return String.format(PAIR, user, small);
  }

  @Benchmark
  public String pair_helper() {
    return FormatRewrites.format(sites, 0, PAIR, new Object[] {user, small});
  }

  @Benchmark
  public String login_jdk() {
    return String.format(LOGIN, user, address);
  }

  @Benchmark
  public String login_helper() {
    return FormatRewrites.format(sites, 1, LOGIN, new Object[] {user, address});
  }

  @Benchmark
  public String items_jdk() {
    return String.format(ITEMS, large);
  }

  @Benchmark
  public String items_helper() {
    return FormatRewrites.format(sites, 2, ITEMS, new Object[] {large});
  }

  @Benchmark
  public String line_jdk() {
    return String.format(LINE, large, total);
  }

  @Benchmark
  public String line_helper() {
    return FormatRewrites.format(sites, 3, LINE, new Object[] {large, total});
  }

  @Benchmark
  public String unsupported_jdk() {
    return String.format(UNSUPPORTED, millis);
  }

  @Benchmark
  public String unsupported_helper() {
    return FormatRewrites.format(sites, 4, UNSUPPORTED, new Object[] {millis});
  }

  /** The unslotted entry point, using the global cache. */
  @Benchmark
  public String items_unslotted_helper() {
    return FormatRewrites.format(ITEMS, new Object[] {large});
  }

  @Benchmark
  public String items_locale_jdk() {
    return String.format(Locale.FRANCE, ITEMS, large);
  }

  @Benchmark
  public String items_locale_helper() {
    return FormatRewrites.format(sites, 5, Locale.FRANCE, ITEMS, new Object[] {large});
  }
}
