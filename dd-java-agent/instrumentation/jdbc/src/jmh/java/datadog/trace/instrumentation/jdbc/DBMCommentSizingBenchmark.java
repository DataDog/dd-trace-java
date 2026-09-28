package datadog.trace.instrumentation.jdbc;

import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;
import java.util.concurrent.TimeUnit;
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
 * <pre>{@code
 * Operation     Scenario       Base ns/op       Sized ns/op      Base B/op  Sized B/op
 * buildComment  short_service   164.8 ±   1.2    107.7 ±  0.8      1629.5       701.5
 * buildComment  short_full      220.0 ±   2.0    203.3 ±  1.2      1893.5      1037.5
 * buildComment  long_full      2155.8 ±  15.5   2109.9 ±  8.6      6477.5      4803.0
 * buildComment  escaped_full   3393.3 ±  79.7   3425.7 ± 25.8     12656.0     10624.0
 * injectSql     short_service   182.6 ±   1.3    151.8 ±  1.3      1987.0      1059.0
 * injectSql     short_full      285.0 ±   2.7    250.6 ±  1.3      2395.0      1539.0
 * injectSql     long_full      3635.6 ±  72.4   3530.2 ± 31.6     17331.0     15656.5
 * injectSql     escaped_full   3378.1 ± 178.6   3395.9 ± 33.0     14968.0     12936.0
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(
    value = 3,
    jvmArgsAppend = {
      "-Xms512m",
      "-Xmx512m",
      "-Ddd.service=orders",
      "-Ddd.env=prod",
      "-Ddd.version=1.2.3"
    })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Threads(1)
@State(Scope.Thread)
public class DBMCommentSizingBenchmark {
  @Param({"short_service", "short_full", "long_full", "escaped_full"})
  public String scenario;

  String[] services = new String[32],
      hosts = new String[32],
      databases = new String[32],
      parents = new String[32],
      queries = new String[32];
  int index;

  @Setup
  public void setup() {
    for (int i = 0; i < 32; i++) {
      services[i] = "orders-db-" + i;
      hosts[i] = "db-" + i + ".internal";
      databases[i] = "orders_" + i;
      parents[i] =
          scenario.equals("short_service")
              ? null
              : "00-12345678901234567890123456789012"
                  + String.format("%02x", i)
                  + "-98765432109876"
                  + String.format("%02x", i)
                  + "-01";
      if (scenario.equals("long_full")) {
        services[i] = repeat("service", 80) + i;
        hosts[i] = repeat("hostname", 30) + i;
        databases[i] = repeat("database", 60) + i;
      }
      if (scenario.equals("escaped_full")) {
        services[i] = repeat("café 東京 &/", 16) + i;
        databases[i] = repeat("orders '€'", 16) + i;
      }
      queries[i] =
          "SELECT * FROM orders WHERE customer_id = "
              + i
              + (scenario.equals("long_full") ? " /* " + repeat("query padding ", 280) + " */" : "")
              + ";";
    }
  }

  static String repeat(String s, int n) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  int next() {
    return index++ & 31;
  }

  @Benchmark
  public String buildComment() {
    int i = next();
    return SharedDBCommenter.buildComment(
        services[i], "postgresql", hosts[i], databases[i], parents[i]);
  }

  @Benchmark
  public String injectSql() {
    int i = next();
    return SQLCommenter.inject(
        queries[i], services[i], "postgresql", hosts[i], databases[i], parents[i], false);
  }
}
