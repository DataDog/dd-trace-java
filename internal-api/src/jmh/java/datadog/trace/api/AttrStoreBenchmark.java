package datadog.trace.api;

import datadog.trace.util.TagSet;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * How much headroom is left in the dense known-tag store? Builds a span's known tags three ways and
 * measures throughput (+ allocation via {@code -prof gc}). Models the real lifecycle — set N tags,
 * then iterate once (serialize).
 *
 * <p>Phase 1 (dense storage inside {@code OptimizedTagMap}) has already landed, so {@code current}
 * below is the LIVE dense store, NOT the old Entry-per-tag design. The Entry[]-&gt;dense win is
 * evidenced elsewhere (petclinic CPU/req, JFR); this benchmark now isolates what's LEFT to chase:
 *
 * <ol>
 *   <li>{@code current}: the live {@link TagMap} ({@code OptimizedTagMap}) — already dense ({@code
 *       long[] knownIds + Object[] knownValues}, no per-tag {@code Entry}), plus the full TagMap
 *       machinery (size bookkeeping, lazy buckets, keyOf upgrade path).
 *   <li>{@code dense}: a bare {@code long[] ids + Object[] values} store — strips the TagMap
 *       machinery, so {@code current} vs {@code dense} measures that overhead. Both box the one
 *       int.
 *   <li>{@code pojo}: a hand-written class with typed fields — the codegen endgame (no {@code
 *       Entry}, no boxing, no arrays-per-tag); shows the ceiling {@code dense}-&gt;{@code pojo}
 *       buys.
 * </ol>
 *
 * Tag set is db.client-like (the dominant PetClinic span): 11 strings + 1 int.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(8)
@State(Scope.Benchmark)
public class AttrStoreBenchmark {
  static final String[] NAMES = {
    "component",
    "span.kind",
    "language",
    "_dd.base_service",
    "db.type",
    "db.instance",
    "db.operation",
    "db.user",
    "db.pool.name",
    "peer.hostname",
    "peer.ipv4",
    "peer.port", // last is the int
  };
  static final int PORT_IDX = 11;
  static final int N = NAMES.length;

  static final long[] IDS = new long[N];
  static final Object[] VALUES = new Object[N]; // string values; port is boxed Integer

  @Setup
  public void setup() {
    for (int i = 0; i < N; ++i) {
      IDS[i] = KnownTags.tagId(i + 1, i, NAMES[i]); // serial=i+1, fieldPos=i
      VALUES[i] = (i == PORT_IDX) ? Integer.valueOf(5432) : ("value-" + i);
    }
    final TagSet.Data nameTable = TagSet.Support.create(NAMES);
    final long[] slotIds = new long[nameTable.names.length];
    for (int i = 0; i < N; ++i) {
      slotIds[TagSet.Support.indexOf(nameTable.hashes, nameTable.names, NAMES[i])] = IDS[i];
    }
    KnownTags.register(
        new KnownTags.Resolver() {
          @Override
          public String nameOf(long tagId) {
            int gs = (int) ((tagId >>> 48) & 0x7FFF);
            return (gs >= 1 && gs <= N) ? NAMES[gs - 1] : null;
          }

          @Override
          public long keyOf(String name) {
            int slot = TagSet.Support.indexOf(nameTable.hashes, nameTable.names, name);
            return slot < 0 ? 0L : slotIds[slot];
          }

          @Override
          public int slotCount() {
            return N;
          }
        });
  }

  @TearDown
  public void tearDown() {
    KnownTags.register(null);
  }

  // ---------- current: OptimizedTagMap (Entry per tag) ----------
  @Benchmark
  public TagMap build_current() {
    TagMap map = TagMap.create();
    for (int i = 0; i < N; ++i) {
      map.set(IDS[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public void buildIter_current(Blackhole bh) {
    TagMap map = TagMap.create();
    for (int i = 0; i < N; ++i) {
      map.set(IDS[i], VALUES[i]);
    }
    for (TagMap.EntryReader e : map) {
      bh.consume(e.tag());
      bh.consume(e.objectValue());
    }
  }

  // ---------- dense: long[] ids + Object[] values ----------
  @Benchmark
  public DenseStore build_dense() {
    DenseStore s = new DenseStore();
    for (int i = 0; i < N; ++i) {
      s.set(IDS[i], VALUES[i]);
    }
    return s;
  }

  @Benchmark
  public void buildIter_dense(Blackhole bh) {
    DenseStore s = new DenseStore();
    for (int i = 0; i < N; ++i) {
      s.set(IDS[i], VALUES[i]);
    }
    for (int i = 0; i < s.size; ++i) {
      bh.consume(KnownTags.nameOf(s.ids[i]));
      bh.consume(s.values[i]);
    }
  }

  // ---------- pojo: typed fields ----------
  @Benchmark
  public DbPojo build_pojo() {
    DbPojo p = new DbPojo();
    for (int i = 0; i < N; ++i) {
      if (i == PORT_IDX) {
        p.set(IDS[i], 5432);
      } else {
        p.set(IDS[i], VALUES[i]);
      }
    }
    return p;
  }

  @Benchmark
  public void buildIter_pojo(Blackhole bh) {
    DbPojo p = new DbPojo();
    for (int i = 0; i < N; ++i) {
      if (i == PORT_IDX) {
        p.set(IDS[i], 5432);
      } else {
        p.set(IDS[i], VALUES[i]);
      }
    }
    p.iterate(bh);
  }

  /** Dense (id, value) store — phase-1 design. */
  static final class DenseStore {
    long[] ids = new long[16];
    Object[] values = new Object[16];
    int size;

    void set(long id, Object v) {
      for (int i = 0; i < size; ++i) {
        if (ids[i] == id) {
          values[i] = v;
          return;
        }
      }
      if (size == ids.length) {
        ids = java.util.Arrays.copyOf(ids, size * 2);
        values = java.util.Arrays.copyOf(values, size * 2);
      }
      ids[size] = id;
      values[size] = v;
      size++;
    }
  }

  /** Hand-written POJO — phase-2 codegen endgame. serial = fieldPos+1 here. */
  static final class DbPojo {
    String component,
        spanKind,
        language,
        baseService,
        dbType,
        dbInstance,
        dbOperation,
        dbUser,
        dbPoolName,
        peerHostname,
        peerIpv4;
    int peerPort;

    void set(long id, Object v) {
      switch ((int) ((id >>> 48) & 0x7FFF)) {
        case 1:
          component = (String) v;
          break;
        case 2:
          spanKind = (String) v;
          break;
        case 3:
          language = (String) v;
          break;
        case 4:
          baseService = (String) v;
          break;
        case 5:
          dbType = (String) v;
          break;
        case 6:
          dbInstance = (String) v;
          break;
        case 7:
          dbOperation = (String) v;
          break;
        case 8:
          dbUser = (String) v;
          break;
        case 9:
          dbPoolName = (String) v;
          break;
        case 10:
          peerHostname = (String) v;
          break;
        case 11:
          peerIpv4 = (String) v;
          break;
        default: /* off-type / unknown -> would bucket */
          break;
      }
    }

    void set(long id, int v) {
      if (((int) ((id >>> 48) & 0x7FFF)) == 12) {
        peerPort = v;
      }
    }

    void iterate(Blackhole bh) {
      bh.consume(component);
      bh.consume(spanKind);
      bh.consume(language);
      bh.consume(baseService);
      bh.consume(dbType);
      bh.consume(dbInstance);
      bh.consume(dbOperation);
      bh.consume(dbUser);
      bh.consume(dbPoolName);
      bh.consume(peerHostname);
      bh.consume(peerIpv4);
      bh.consume(peerPort);
    }
  }
}
