package datadog.trace.api;

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
import org.openjdk.jmh.infra.Blackhole;

/**
 * Allocation per span-sized TagMap with the dense store enabled. Run with {@code -prof gc} and read
 * {@code gc.alloc.rate.norm} (B/op): allocation is deterministic, throughput here is not tuned.
 *
 * <p>Inputs: {@code tagCount} tags per map, drawn from the slotted known tags of an HTTP server
 * span (dense) and custom names (bucketed). {@code scenario} sets the mix: {@code custom} (all
 * bucketed -- the baseline), {@code mixed} (about 70% slotted), {@code allSlotted}. {@code
 * buildMapById} sets the slotted tags by id, the path instrumentation is moving to.
 *
 * <p>The flag is per JVM, so this class forks with it on. For the store-off baseline, run with
 * {@code -jvmArgsAppend -Ddd.trace.experimental.dense.tags.enabled=false}.
 *
 * <p>Results, B/op ({@code buildMap}; {@code buildMapById} matched it in every case), Zulu 17.0.7,
 * Apple Silicon, {@code -prof gc -f 1 -wi 1 -i 2}, 2026-10-09:
 *
 * <pre>
 * scenario     tagCount   store off   store on
 * custom              7         472        472
 * custom             12         808        808
 * mixed               7         520        456
 * mixed              12         856        504
 * allSlotted          7         568        232
 * allSlotted         12         856        232
 * </pre>
 *
 * <p>Custom tags are unchanged. With the store on, a map of up to 12 slotted tags costs the same
 * (the dense arrays start at 12), and {@code buildAndForEach} adds a flat 16 B/op (one reused
 * flyweight), not a per-tag Entry.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Ddd.trace.experimental.dense.tags.enabled=true"})
@Threads(1)
public class DenseStoreAllocBenchmark {
  static final long[] SLOTTED_IDS = {
    KnownTags.COMPONENT_ID,
    KnownTags.SPAN_KIND_ID,
    KnownTags.SERVICE_ID,
    KnownTags.HTTP_METHOD_ID,
    KnownTags.HTTP_URL_ID,
    KnownTags.HTTP_ROUTE_ID,
    KnownTags.HTTP_STATUS_CODE_ID,
    KnownTags.HTTP_HOSTNAME_ID,
    KnownTags.HTTP_USERAGENT_ID,
    KnownTags.NETWORK_PROTOCOL_VERSION_ID,
    KnownTags.DD_INTEGRATION_ID,
    KnownTags.ERROR_TYPE_ID,
  };

  @Param({"custom", "mixed", "allSlotted"})
  String scenario;

  @Param({"7", "12"})
  int tagCount;

  private String[] names;
  private long[] ids; // 0 for a custom tag
  private String[] values;

  @Setup(Level.Trial)
  public void setup() {
    for (long id : SLOTTED_IDS) {
      if (!KnownTagCodec.hasSlot(id)) {
        throw new IllegalStateException(KnownTagCodec.nameOf(id) + " has no dense-store slot");
      }
    }
    int slotted;
    if ("allSlotted".equals(scenario)) {
      slotted = tagCount;
    } else if ("mixed".equals(scenario)) {
      slotted = (tagCount * 7) / 10;
    } else {
      slotted = 0;
    }
    this.names = new String[tagCount];
    this.ids = new long[tagCount];
    this.values = new String[tagCount];
    for (int i = 0; i < tagCount; i++) {
      if (i < slotted) {
        this.ids[i] = SLOTTED_IDS[i];
        this.names[i] = KnownTagCodec.nameOf(SLOTTED_IDS[i]);
      } else {
        this.names[i] = "custom.tag." + i;
      }
      this.values[i] = "value-" + i;
    }
  }

  @Benchmark
  public TagMap buildMap() {
    TagMap m = TagMap.create();
    for (int i = 0; i < tagCount; i++) {
      m.set(names[i], values[i]);
    }
    return m;
  }

  @Benchmark
  public TagMap buildMapById() {
    TagMap m = TagMap.create();
    for (int i = 0; i < tagCount; i++) {
      long id = ids[i];
      if (id != 0) {
        m.set(id, values[i]);
      } else {
        m.set(names[i], values[i]);
      }
    }
    return m;
  }

  @Benchmark
  public void buildAndForEach(Blackhole bh) {
    TagMap m = buildMap();
    m.forEach(reader -> bh.consume(reader.objectValue()));
    bh.consume(m);
  }

  @Benchmark
  public void buildAndIterate(Blackhole bh) {
    TagMap m = buildMap();
    for (TagMap.EntryReader reader : m) {
      bh.consume(reader.objectValue());
    }
    bh.consume(m);
  }
}
