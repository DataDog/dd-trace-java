package datadog.trace.api;

import datadog.trace.api.TagMap.Entry;
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
 * <p>Placed in {@code datadog.trace.api} so it can build tag ids with the same {@code nameHash} the
 * runtime uses ({@link TagMap.Entry#_hash}); a mismatch would only matter on the bucket-fallback
 * path, but keeping it exact makes the comparison faithful.
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

  static int nameHash(String tag) {
    int hash = tag.hashCode();
    return hash == 0 ? 0xDD06 : hash ^ (hash >>> 16);
  }

  @Setup(Level.Trial)
  public void setup() {
    for (int i = 0; i < NAMES.length; ++i) {
      // globalSerial = i + 1 (unique, non-zero); fieldPos = i (distinct - no collisions)
      IDS[i] = ((long) (i + 1) << 48) | ((long) i << 32) | (nameHash(NAMES[i]) & 0xFFFFFFFFL);
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
        });
    // sanity: assert _hash matches our nameHash so string lookups hit the same bucket if they ever
    // fall through (they shouldn't here, but keep the comparison honest)
    if (Entry._hash(NAMES[0]) != nameHash(NAMES[0])) {
      throw new IllegalStateException("nameHash mismatch with TagMap.Entry._hash");
    }

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
