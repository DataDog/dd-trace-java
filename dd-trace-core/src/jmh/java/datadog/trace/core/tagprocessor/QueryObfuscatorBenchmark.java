package datadog.trace.core.tagprocessor;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.api.DDTags;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 5, time = 200, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = MILLISECONDS)
@OutputTimeUnit(MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class QueryObfuscatorBenchmark {

  private final QueryObfuscator obfuscator;

  @Param({
    "key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2",
    "json=%7B%20%22sign%22%3A%20%22%7B0x03cb9f67%2C0xdbbc%2C0x4cb8%2C%7B0xb9%2C0x66%2C0x32%2C0x99%2C0x51%2C0xe1%2C0x09%2C0x34%7D%7D%22%7D"
  })
  String queryString;

  Map<String, Object> tags;

  public QueryObfuscatorBenchmark() {
    this.obfuscator = new QueryObfuscator(null);
  }

  @Setup(Level.Invocation)
  public void setup() {
    tags = new LinkedHashMap<>();
    tags.put(DDTags.HTTP_QUERY, queryString);
  }

  @Benchmark
  public void processTagsQuery() {
    obfuscator.processTags(tags);
  }
}
