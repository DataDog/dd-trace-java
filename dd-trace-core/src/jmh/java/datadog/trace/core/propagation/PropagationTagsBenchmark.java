package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 120, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 120, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressForbidden
public class PropagationTagsBenchmark {

  /**
   * In order to avoid JMH splitting up the strings, all ',' are replaced by '|' in the parameter.
   * The parameter is split at '|' and the first element is the propagation tags header type. The
   * rest of the elements are joined with ',' to form the header to be parsed.
   */
  @Param({
    "datadog|_dd.p.anytag=value|_dd.p.dm=934086a686-4",
    "w3c|dd=s:1;o:some;t.anytag:value;t.dm:934086a686-4",
    "w3c|foo=bar|dd=s:1;o:some;t.anytag:value;t.dm:934086a686-4|bar=baz",
    "w3c|foo=bar|dd=s:1;o:some;t.anytag:value;other:value;t.dm:934086a686-4|bar=baz"
  })
  String extractHeaderType;

  @Param({"datadog", "w3c"})
  String injectHeaderType;

  PropagationTags.Factory factory;
  PropagationTags.HeaderType extractHT;
  PropagationTags.HeaderType injectHT;
  String header;

  @Setup(Level.Trial)
  public void setUp() {
    factory = PropagationTags.factory();
    CharSequence[] parts = extractHeaderType.split("\\|");
    extractHT = PropagationTags.HeaderType.valueOf(((String) parts[0]).toUpperCase());
    header = String.join(",", Arrays.stream(parts, 1, parts.length)::iterator);
    injectHT = PropagationTags.HeaderType.valueOf(injectHeaderType.toUpperCase());
  }

  @Benchmark
  public void extractHeader(Blackhole blackhole) {
    blackhole.consume(factory.fromHeaderValue(extractHT, header));
  }

  @Benchmark
  public void extractInjectHeader(Blackhole blackhole) {
    PropagationTags pt = factory.fromHeaderValue(extractHT, header);
    blackhole.consume(pt.headerValue(injectHT));
    blackhole.consume(pt);
  }
}
