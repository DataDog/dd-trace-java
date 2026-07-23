package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.Tags;
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
 * Deterministic allocation A/B for the dense known-tag store, using the REAL {@link KnownTags}
 * resolver (a {@code StringIndex} probe + a constant-returning {@code switch} — allocation-free,
 * exactly like production). An earlier synthetic prefix resolver allocated in {@code keyOf}
 * (substring) and {@code nameOf} (concat), contaminating the dense arm; this measures the store,
 * not the resolver.
 *
 * <p>Models how a real span's tags route: {@code today} = all custom (what ships now — every tag
 * buckets, since nothing is registered as known), {@code dense} = the same tag count with a
 * realistic fraction routed to the dense store (real known tag names) and the rest custom. Run with
 * {@code -prof gc}; the {@code gc.alloc.rate.norm} (B/op) delta at the same {@code tagCount} is
 * what enabling the dense store does to a real span's per-build allocation.
 *
 * <p><b>Results — buildMap, JDK 17 (Zulu 17.0.7, Apple Silicon), {@code -prof gc -f 1 -wi 2 -i 3},
 * 2026-07-08.</b> Allocation is deterministic (±0.001 B/op); throughput on this run is NOT
 * trustworthy (single fork, short) — read B/op only.
 *
 * <pre>{@code
 * scenario    tagCount=7   tagCount=12
 * today          408 B/op     704 B/op
 * dense          376 B/op     416 B/op
 * allKnown       176 B/op     400 B/op
 * }</pre>
 *
 * <p>Gate met: {@code dense < today} at both counts (the over-provision artifact is gone). The
 * Entry-less win scales with the known-tag fraction — ~8% at 7 tags (~70% known), ~41% at 12;
 * {@code allKnown} (the codegen endgame / read-through parent shape) reaches ~57% at 7.
 *
 * <p><b>Serialize paths (same run, B/op).</b> {@code buildAndSerialize} (alloc-free {@code forEach}
 * flyweight) adds a flat +16 B/op over {@code buildMap} in every scenario (7: 392, 12: 432 dense).
 * {@code buildAndSerializeViaIterator} — the {@code EntryReader} enhanced-for modeling the count
 * pre-pass at {@code TraceMapperV0_4:95} — adds a CONSTANT per-call cost (+56 custom / +80 dense,
 * identical at 7 and 12 tags): that flat-vs-tagCount signature is the {@code EntryReaderIterator}
 * OBJECT, NOT per-tag Entry — the iterator reuses a dense flyweight (TagMap:2182/2652). So the
 * dense win SURVIVES serialization; the only nit is {@code iterator()} allocating one Iterator per
 * call, which {@code forEach} avoids and which can be recycled away.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
@Threads(1)
public class DenseStoreAllocBenchmark {

  // Real stored (dense-routed) tag names — a realistic web/db span's known set.
  static final String[] KNOWN =
      new String[] {
        DDTags.BASE_SERVICE,
        Tags.VERSION,
        Tags.COMPONENT,
        Tags.SPAN_KIND,
        Tags.HTTP_METHOD,
        Tags.HTTP_ROUTE,
        Tags.DB_TYPE,
        Tags.DB_INSTANCE,
        Tags.PEER_HOSTNAME,
        Tags.DB_USER,
        DDTags.LANGUAGE_TAG_KEY,
        Tags.PEER_PORT,
      };

  // today = all custom (all bucket, what ships now); dense = ~70% known + custom (a real span);
  // allKnown = 100% known (the trace-tier read-through parent's shape — exercises lazy buckets).
  @Param({"today", "dense", "allKnown"})
  String scenario;

  @Param({"7", "12"})
  int tagCount;

  private String[] keys;
  private String[] values;
  // Per-type sizing hint seeded to this scenario's known-tag count -- what a SpanPrototype
  // supplies.
  private SizingHint prototype;

  @Setup(Level.Trial)
  public void setup() {
    KnownTags.init(); // registers the real (allocation-free) resolver
    int knownCount;
    if ("allKnown".equals(scenario)) {
      knownCount = tagCount; // 100% known (<= KNOWN.length)
    } else if ("dense".equals(scenario)) {
      knownCount = (tagCount * 7) / 10; // ~70% known + custom
    } else {
      knownCount = 0; // today: all custom (all bucket)
    }
    this.keys = new String[tagCount];
    this.values = new String[tagCount];
    for (int i = 0; i < tagCount; i++) {
      this.keys[i] = i < knownCount ? KNOWN[i] : "custom.tag." + i;
      this.values[i] = "value-" + i;
    }
    // Size the dense store to the known-tag count (the "end state" per-type sizing).
    this.prototype = new SizingHint("bench", 0, Math.max(knownCount, 1));
  }

  /** Current: generic default dense capacity (KNOWN_INIT_CAP=12) -- over-provisions small types. */
  @Benchmark
  public TagMap buildMap() {
    TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(keys[i], values[i]);
    }
    return m;
  }

  /**
   * End state: dense store sized per-type via a SpanPrototype (SizingHint) -- no over-provision.
   */
  @Benchmark
  public TagMap buildMapSized() {
    TagMap m = TagMap.create(prototype);
    for (int i = 0; i < tagCount; i++) {
      m.set(keys[i], values[i]);
    }
    return m;
  }

  @Benchmark
  public void buildAndSerialize(Blackhole bh) {
    TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(keys[i], values[i]);
    }
    // forEach: the alloc-free flyweight emit for dense
    m.forEach(reader -> bh.consume(reader.objectValue()));
    bh.consume(m);
  }

  @Benchmark
  public void buildAndSerializeViaIterator(Blackhole bh) {
    TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(keys[i], values[i]);
    }
    // models the REAL serializer's count pre-pass (TraceMapperV0_4:95). The EntryReader iterator
    // uses a reused dense flyweight (NO per-tag Entry alloc — TagMap:2182/2652), so the dense win
    // SURVIVES; the only extra cost vs forEach is the EntryReaderIterator object itself (a fixed
    // per-call cost, constant across tagCount — not per-tag). forEach avoids even that.
    for (TagMap.EntryReader reader : m) {
      bh.consume(reader.objectValue());
    }
    bh.consume(m);
  }
}
