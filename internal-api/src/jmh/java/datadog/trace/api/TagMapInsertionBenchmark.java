package datadog.trace.api;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares tag insertion / lookup by generated tag id vs by string name, with a {@link
 * KnownTags.Resolver} registered (the production configuration once code generation is live).
 *
 * <p>Tag ids are built via {@link KnownTags#tagId} (which uses the runtime's own name hash), so the
 * comparison is faithful even on the bucket-fallback path.
 *
 * <p>The tags use distinct {@code fieldPos} values (no collisions), so every known tag lands in its
 * positional slot. byId skips string hashing and the keyOf round-trip entirely; byString pays
 * keyOf(name) to resolve the id before slotting.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(1)
@State(Scope.Benchmark)
public class TagMapInsertionBenchmark {
  // a representative HTTP-server-ish tag set
  static final String[] NAMES = {
    "http.request.method",
    "http.response.status_code",
    "http.route",
    "url.path",
    "url.scheme",
    "server.address",
    "server.port",
    "client.address",
    "network.protocol.version",
    "user_agent.original",
    "span.kind",
    "component",
    "language",
    "error",
    "resource.name",
    "service.name",
    "operation.name",
    "env",
  };

  static final long[] IDS = new long[NAMES.length];
  static final Object[] VALUES = new Object[NAMES.length];

  // a pre-populated (slotted) map for the read benchmarks; built in setup once IDS exist
  TagMap readMap;

  @Setup(Level.Trial)
  public void setup() {
    for (int i = 0; i < NAMES.length; ++i) {
      // globalSerial = i + 1 (unique, non-zero); fieldPos = i (distinct - no collisions)
      IDS[i] = KnownTags.tagId(i + 1, i, NAMES[i]);
      VALUES[i] = "value-" + i;
    }
    // Representative resolver: nameOf is a dense array index by globalSerial; keyOf is a hash-table
    // lookup (a stand-in for a generated minimal-perfect-hash / open-addressed name->id table).
    // A linear scan here would make insertByString look artificially bad and misrepresent the cost.
    final java.util.HashMap<String, Long> nameToId = new java.util.HashMap<>(NAMES.length * 2);
    for (int i = 0; i < NAMES.length; ++i) {
      nameToId.put(NAMES[i], IDS[i]);
    }
    KnownTags.register(
        new KnownTags.Resolver() {
          @Override
          public String nameOf(long tagId) {
            int globalSerial = (int) (tagId >>> 48);
            return (globalSerial >= 1 && globalSerial <= NAMES.length)
                ? NAMES[globalSerial - 1]
                : null;
          }

          @Override
          public long keyOf(String name) {
            Long id = nameToId.get(name);
            return id == null ? 0L : id;
          }

          @Override
          public int slotCount() {
            return NAMES.length; // fieldPos 0..NAMES.length-1
          }
        });

    // pre-populate the read map by id (entries land in their slots)
    this.readMap = TagMap.create();
    for (int i = 0; i < IDS.length; ++i) {
      this.readMap.set(IDS[i], VALUES[i]);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    KnownTags.register(null);
  }

  @Benchmark
  public TagMap insertById() {
    TagMap map = TagMap.create();
    for (int i = 0; i < IDS.length; ++i) {
      map.set(IDS[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public TagMap insertByString() {
    TagMap map = TagMap.create();
    for (int i = 0; i < NAMES.length; ++i) {
      map.set(NAMES[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public void getById(Blackhole bh) {
    for (int i = 0; i < IDS.length; ++i) {
      bh.consume(this.readMap.getEntry(IDS[i]));
    }
  }

  @Benchmark
  public void getByString(Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(this.readMap.getEntry(NAMES[i]));
    }
  }
}
