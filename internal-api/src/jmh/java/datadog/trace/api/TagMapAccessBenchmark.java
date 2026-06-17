package datadog.trace.api;

import datadog.trace.util.TagSet;
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
 * <p>byId stores straight into the dense known-tag store at its positional slot ({@code
 * knownValues[fieldPos]}, O(1), no scan); byString pays {@code keyOf(name)} to resolve the id first
 * (via the real {@link datadog.trace.util.TagSet} table) and then slots it the same way. The bucket
 * baseline (no resolver, master-equivalent) is {@link TagMapAccessBaselineBenchmark}. <code>
 * Apple M1 Max (10 core) - 8 threads - 1 fork - Java 8 (Zulu 8.0.382) - positional dense store
 *
 * Benchmark                            Mode  Cnt        Score        Error  Units
 * insertById                          thrpt    5  126235943.1 ± 11653584.6  ops/s
 * insertByString                      thrpt    5   57355057.5 ±  2976623.2  ops/s
 * getObjectById                       thrpt    5  129726670.1 ± 10877596.1  ops/s
 * getObjectByString                   thrpt    5   73544340.8 ±  1349944.7  ops/s
 * getEntryById                        thrpt    5  129117822.8 ± 16455290.0  ops/s
 * getEntryByString                    thrpt    5   73422181.5 ±  2210885.4  ops/s
 * baseline.insertByString_noResolver  thrpt    5   43334158.2 ±  4699836.5  ops/s  (master path)
 * baseline.getByString_noResolver     thrpt    5  107969497.0 ±  7160811.9  ops/s  (master path)
 * </code>
 *
 * <ul>
 *   <li><b>getObject by id vs by name: 129.7M vs 73.5M (~1.77x)</b> — the common read. The whole
 *       gap is {@code keyOf}; both hit the slot and return the raw value with no Entry. Id-keyed
 *       value reads win.
 *   <li><b>getObject ~= getEntry</b> (130M ~= 129M): the Entry "materialization penalty" vanishes
 *       for value use — escape analysis scalar-replaces the transient Entry when the caller
 *       consumes its value rather than retaining it, so {@code getEntry} needs no replacement here.
 *       (getEntryReader was measured and dropped: its eager name resolution made it the slowest
 *       read.)
 *   <li><b>insertById ~3x the bucket baseline</b> (126M vs 43M) — O(1) positional claim + no
 *       per-tag Entry; <b>insertByString +32%</b> (57M vs 43M) even paying {@code keyOf}, so the
 *       former name-insert regression is gone.
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(8)
@State(Scope.Benchmark)
public class TagMapAccessBenchmark {
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

  // globalSerial = i + 1 (unique, non-zero); fieldPos = i (the positional slot in the dense store)
  static final long[] IDS = new long[NAMES.length];
  static final Object[] VALUES = new Object[NAMES.length];

  static {
    for (int i = 0; i < NAMES.length; ++i) {
      IDS[i] = KnownTags.tagId(i + 1, i, NAMES[i]);
      VALUES[i] = "value-" + i;
    }
    // Register the resolver at CLASS INIT, not in @Setup: a benchmark-class @Setup and the
    // per-thread ReadMap @Setup have no guaranteed cross-scope ordering, but class init does (any
    // access to IDS triggers it before ReadMap.build runs). Process-global for this benchmark fork.
    // nameOf is a dense array index by globalSerial; keyOf goes through the real open-addressed
    // TagSet table (the algorithm KnownTagIds uses in production).
    final TagSet.Data nameTable = TagSet.Support.create(NAMES);
    final long[] slotIds = new long[nameTable.names.length];
    for (int i = 0; i < NAMES.length; ++i) {
      slotIds[TagSet.Support.indexOf(nameTable.hashes, nameTable.names, NAMES[i])] = IDS[i];
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
            int slot = TagSet.Support.indexOf(nameTable.hashes, nameTable.names, name);
            return slot < 0 ? 0L : slotIds[slot];
          }

          @Override
          public int slotCount() {
            return NAMES.length; // fieldPos 0..NAMES.length-1
          }
        });
  }

  /**
   * Pre-populated read map, PER-THREAD (Scope.Thread): each thread owns its map AND its reused
   * reader flyweight, so getEntryReader doesn't contend on a shared flyweight under @Threads(8).
   */
  @State(Scope.Thread)
  public static class ReadMap {
    OptimizedTagMap map;

    @Setup(Level.Trial)
    public void build() {
      this.map = (OptimizedTagMap) TagMap.create();
      for (int i = 0; i < IDS.length; ++i) {
        this.map.set(IDS[i], VALUES[i]);
      }
    }
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

  // ---- value reads (getObject - raw value, no Entry; the common read) ----
  @Benchmark
  public void getObjectById(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < IDS.length; ++i) {
      bh.consume(rm.map.getObject(IDS[i]));
    }
  }

  @Benchmark
  public void getObjectByString(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(rm.map.getObject(NAMES[i]));
    }
  }

  // ---- entry reads (materializes an Entry per call; EA elides it for transient value use) ----
  @Benchmark
  public void getEntryById(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < IDS.length; ++i) {
      bh.consume(rm.map.getEntry(IDS[i]).objectValue());
    }
  }

  @Benchmark
  public void getEntryByString(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(rm.map.getEntry(NAMES[i]).objectValue());
    }
  }
}
