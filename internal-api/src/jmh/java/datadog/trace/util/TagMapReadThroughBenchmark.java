package datadog.trace.util;

import datadog.trace.api.TagMap;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Models span-build tag assembly with vs without read-through of the shared trace-level bundle.
 *
 * <ul>
 *   <li><b>copyDown</b> — today's path: {@code putAll} the (frozen) trace-level bundle into the
 *       fresh span map, then set the span-specific tags. {@code putAll}-into-empty shares the
 *       frozen entry references (bucket-clone), so this does NOT allocate new Entry objects for the
 *       trace tags — its cost is cloned {@code BucketGroup}s plus the collisions caused by the
 *       trace tags sharing the local buckets with the span tags.
 *   <li><b>readThrough</b> — attach the frozen bundle as a read-through parent; only the
 *       span-specific tags are stored locally.
 * </ul>
 *
 * <p>Run with {@code -prof gc}; the B/op delta is the per-span allocation read-through saves. Both
 * arms set the same span tags, so the delta isolates the trace-bundle handling. {@code
 * traceTagCount} sweeps the bundle size — the win scales with it (more trace tags → more cloned
 * BucketGroups and local collisions avoided). {@code traceTagCount = 7} ≈ a realistic
 * mergedTracerTags (env, version, language, runtime-id, a propagation tag, a couple global tags).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@Threads(8)
public class TagMapReadThroughBenchmark {

  @Param({"3", "7", "15"})
  int traceTagCount;

  private TagMap traceTags;

  @Setup(Level.Trial)
  public void setup() {
    TagMap m = TagMap.create(Math.max(16, traceTagCount * 2));
    for (int i = 0; i < traceTagCount; i++) {
      m.set("_dd.trace.tag." + i, "trace-value-" + i);
    }
    this.traceTags = m.freeze();
  }

  @Benchmark
  public TagMap copyDown() {
    TagMap m = TagMap.create(16);
    m.putAll(traceTags); // putAll-into-empty: shares frozen entries, clones BucketGroups
    setSpanTags(m);
    return m;
  }

  @Benchmark
  public TagMap readThrough() {
    // no copy; trace tags read through the shared frozen parent (fixed at construction)
    TagMap m = TagMap.createFromParent(traceTags);
    setSpanTags(m);
    return m;
  }

  private static void setSpanTags(TagMap m) {
    m.set("http.method", "GET");
    m.set("http.url", "/api/checkout/cart");
    m.set("component", "spring-web-controller");
    m.set("span.kind", "server");
    m.set("http.status_code", 200);
  }
}
