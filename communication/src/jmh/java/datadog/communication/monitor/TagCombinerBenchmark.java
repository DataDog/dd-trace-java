package datadog.communication.monitor;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.ServiceNaming;
import java.util.function.Function;
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
@Warmup(iterations = 1, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 2, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class TagCombinerBenchmark {

  @Param({"", "some:thing,other:value"})
  String tagParam;

  String[] varTags;

  OldTagCombiner oldCombiner;

  DDAgentStatsDClientManager.TagCombiner newCombiner;

  @Setup(Level.Trial)
  public void setup() {
    oldCombiner =
        new OldTagCombiner(
            new String[] {"env:test", "version:12", "language:java", "service:myservice"});
    newCombiner =
        new DDAgentStatsDClientManager.TagCombiner(
            new String[] {"env:test", "version:12", "language:java"},
            new ServiceNaming("myservice", false));
    if (tagParam.isEmpty()) {
      varTags = null;
    } else {
      varTags = tagParam.split(",");
    }
  }

  @Benchmark
  public void benchStaticServiceName(final Blackhole blackhole) {
    blackhole.consume(oldCombiner.apply(varTags));
  }

  @Benchmark
  public void benchDynamicServiceName(final Blackhole blackhole) {
    blackhole.consume(newCombiner.apply(varTags));
  }

  public static class OldTagCombiner implements Function<String[], String[]> {
    private final DDCache<String[], String[]> combinedTags = DDCaches.newFixedSizeArrayKeyCache(64);
    // single-element array containing the pre-packed constant tags
    private final String[] packedTags;
    private final Function<String[], String[]> tagsInserter;

    public OldTagCombiner(final String[] constantTags) {
      this.packedTags = pack(constantTags);
      this.tagsInserter =
          tags -> {
            // extend per-call array by one to add the pre-packed constant tags
            String[] result = new String[tags.length + 1];
            System.arraycopy(tags, 0, result, 1, tags.length);
            result[0] = packedTags[0];
            return result;
          };
    }

    @Override
    public String[] apply(final String[] tags) {
      if (null == tags || tags.length == 0) {
        return packedTags; // no per-call tags so we can use the pre-packed array
      } else {
        return combinedTags.computeIfAbsent(tags, tagsInserter);
      }
    }

    private static String[] pack(final String[] tags) {
      StringBuilder buf = new StringBuilder(tags[0]);
      for (int i = 1; i < tags.length; i++) {
        buf.append(',').append(tags[i]);
      }
      return new String[] {buf.toString()};
    }
  }
}
