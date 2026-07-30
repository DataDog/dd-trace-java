package datadog.trace.util;

import datadog.trace.util.LightMap.EmbeddingSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures three candidate grow triggers for {@code LightMap}, to decide whether to reframe "when
 * to grow" from a load-factor threshold to a maximum-probe bound.
 *
 * <ul>
 *   <li>{@code FULL} -- grow only when the table is physically full (the pre-existing spine
 *       behavior; the probe walks the whole table before giving up).
 *   <li>{@code LOAD_FACTOR} -- grow before the live count would exceed 0.75 of the slots (the
 *       currently committed object-tier policy; needs a maintained live count).
 *   <li>{@code MAX_PROBES} -- grow when an insertion would land more than {@code K} slots from its
 *       home slot (stateless; derived from the probe walk, so it can live entirely in the spine).
 * </ul>
 *
 * <p>All three arms drive the <em>same</em> real {@code EmbeddingSupport} primitives (this
 * benchmark lives in {@code datadog.trace.util} so it can call the package-private spine directly),
 * so the only difference between arms is the grow decision.
 *
 * <p>The {@code @Benchmark} methods answer the <b>hot-path tax</b> question (does the extra probe
 * distance check cost anything on the tiny, miss-dominated majority case?). The {@link #main}
 * method runs a deterministic, timing-free analysis of the <b>behavior</b> question (grow frequency
 * and the probe-length distribution each trigger produces), including the tail that a mean would
 * hide.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@State(Scope.Thread)
public class LightMapGrowBenchmark {

  static final int SEED_SLOTS = 8;
  static final int LOAD_FACTOR_RESERVE_SHIFT = 2; // 1/4 reserve => 0.75 trigger
  static final int MAX_PROBES = 8;

  public enum Trigger {
    FULL,
    LOAD_FACTOR,
    MAX_PROBES
  }

  public enum Regime {
    // springweb6 localAttributes: a handful of interned keys, get-heavy, some maps stay empty.
    TINY,
    // where triggers begin to diverge.
    MEDIUM,
    // keys crafted to collide to one home slot -- where MAX_PROBES should bound the tail that
    // LOAD_FACTOR / FULL let run long.
    ADVERSARIAL
  }

  // ---- key sets -------------------------------------------------------------------------------

  static String[] tinyKeys() {
    return new String[] {
      "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern",
      "org.springframework.web.servlet.HandlerMapping.pathWithinHandlerMapping",
      "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables",
      "org.springframework.web.servlet.HandlerMapping.producibleMediaTypes",
      "org.springframework.web.servlet.View.selectedContentType"
    };
  }

  static String[] mediumKeys() {
    String[] keys = new String[64];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = "attribute.key.number." + i;
    }
    return keys;
  }

  // Strings whose home slot (after the spine's hash spread) collides mod 64, and therefore also mod
  // every smaller power-of-two size the map passes through while growing from the 8-slot seed.
  static String[] adversarialKeys(int count) {
    List<String> keys = new ArrayList<>(count);
    int target = EmbeddingSupport.preferredSlot(64, "seed".hashCode());
    for (int i = 0; keys.size() < count; i++) {
      String candidate = "adv" + i;
      if (EmbeddingSupport.preferredSlot(64, candidate.hashCode()) == target) {
        keys.add(candidate);
      }
    }
    return keys.toArray(new String[0]);
  }

  static String[] keysFor(Regime regime) {
    switch (regime) {
      case TINY:
        return tinyKeys();
      case MEDIUM:
        return mediumKeys();
      case ADVERSARIAL:
        return adversarialKeys(48);
      default:
        throw new IllegalStateException();
    }
  }

  // ---- the shared, trigger-parameterized insert over the real spine ---------------------------

  /** Per-map mutable state so LOAD_FACTOR can consult a live count and we can tally grows. */
  static final class Cursor {
    Object[] data;
    int size;
    int grows;
  }

  static void put(Trigger trigger, int k, Cursor c, String key, Object value) {
    Object[] data = c.data;
    if (data == null) {
      c.data = EmbeddingSupport.newMapData(SEED_SLOTS, key, value);
      c.size = 1;
      return;
    }

    int numSlots = EmbeddingSupport.numSlots(data);
    int slot = EmbeddingSupport.findInsertionSlot(data, numSlots, key);
    if (slot >= 0) {
      // overwrite -- no size change, no grow
      data[slot + numSlots] = value;
      return;
    }

    boolean grow;
    if (slot == EmbeddingSupport.SLOT_CAPACITY_REACHED) {
      grow = true;
    } else {
      int available = EmbeddingSupport.flip(slot);
      switch (trigger) {
        case FULL:
          grow = false;
          break;
        case LOAD_FACTOR:
          grow = c.size + 1 > numSlots - (numSlots >> LOAD_FACTOR_RESERVE_SHIFT);
          break;
        case MAX_PROBES:
          {
            int home = EmbeddingSupport.preferredSlot(numSlots, key.hashCode());
            int distance = (available - home) & (numSlots - 1);
            grow = distance >= k;
            break;
          }
        default:
          grow = false;
      }
      if (!grow) {
        data[available] = key;
        data[available + numSlots] = value;
        c.size++;
        return;
      }
    }

    data = EmbeddingSupport.expandMapData(data);
    c.grows++;
    EmbeddingSupport.newMapUncheckedInsert(data, EmbeddingSupport.numSlots(data), key, value);
    c.data = data;
    c.size++;
  }

  static Object[] build(Trigger trigger, int k, String[] keys) {
    Cursor c = new Cursor();
    for (int i = 0; i < keys.length; i++) {
      put(trigger, k, c, keys[i], i);
    }
    return c.data;
  }

  // ---- JMH state ------------------------------------------------------------------------------

  @Param({"FULL", "LOAD_FACTOR", "MAX_PROBES"})
  Trigger trigger;

  @Param({"TINY", "MEDIUM", "ADVERSARIAL"})
  Regime regime;

  String[] insertionKeys;
  String[] lookupKeys; // distinct instances (equals path) plus misses
  Object[] prebuilt;
  int index;

  @Setup(Level.Trial)
  public void setUp() {
    insertionKeys = keysFor(regime);

    // Lookup mix: distinct-instance copies of half the present keys (hits, via equals) interleaved
    // with absent keys (misses) -- the miss-dominated read pattern of the wrapper.
    lookupKeys = new String[insertionKeys.length];
    for (int i = 0; i < insertionKeys.length; i++) {
      lookupKeys[i] = (i % 2 == 0) ? new String(insertionKeys[i]) : ("absent." + i);
    }

    prebuilt = build(trigger, MAX_PROBES, insertionKeys);
  }

  String nextLookupKey() {
    if (++index >= lookupKeys.length) index = 0;
    return lookupKeys[index];
  }

  @Benchmark
  public Object[] build() {
    return build(trigger, MAX_PROBES, insertionKeys);
  }

  @Benchmark
  public Object get() {
    return EmbeddingSupport.get(prebuilt, nextLookupKey());
  }

  @Benchmark
  public void buildThenReadAll(Blackhole bh) {
    Object[] data = build(trigger, MAX_PROBES, insertionKeys);
    for (int i = 0; i < lookupKeys.length; i++) {
      bh.consume(EmbeddingSupport.get(data, lookupKeys[i]));
    }
    bh.consume(data);
  }

  // ---- deterministic behavior analysis (run via main, no timing) ------------------------------

  static int[] displacements(Object[] data) {
    int numSlots = EmbeddingSupport.numSlots(data);
    List<Integer> ds = new ArrayList<>();
    for (int slot = 0; slot < numSlots; slot++) {
      String key = (String) data[slot];
      if (key == null || EmbeddingSupport.isRemoved(key)) continue;
      int home = EmbeddingSupport.preferredSlot(numSlots, key.hashCode());
      ds.add((slot - home) & (numSlots - 1));
    }
    int[] out = new int[ds.size()];
    for (int i = 0; i < out.length; i++) out[i] = ds.get(i);
    Arrays.sort(out);
    return out;
  }

  static int pct(int[] sorted, double p) {
    if (sorted.length == 0) return 0;
    int idx = (int) Math.ceil(p * sorted.length) - 1;
    if (idx < 0) idx = 0;
    if (idx >= sorted.length) idx = sorted.length - 1;
    return sorted[idx];
  }

  static double mean(int[] xs) {
    if (xs.length == 0) return 0;
    long sum = 0;
    for (int x : xs) sum += x;
    return (double) sum / xs.length;
  }

  public static void main(String[] args) {
    int[] ks = {4, 8};
    System.out.printf(
        "%-12s %-12s %6s %6s %6s %6s %6s %6s %6s %6s%n",
        "regime", "trigger", "n", "slots", "LF", "grows", "meanP", "p90", "p99", "maxP");
    for (Regime regime : Regime.values()) {
      String[] keys = keysFor(regime);
      // FULL and LOAD_FACTOR ignore K; MAX_PROBES reported for each K.
      reportRow(regime, Trigger.FULL, 0, keys);
      reportRow(regime, Trigger.LOAD_FACTOR, 0, keys);
      for (int k : ks) {
        reportRow(regime, Trigger.MAX_PROBES, k, keys);
      }
      System.out.println();
    }
  }

  static void reportRow(Regime regime, Trigger trigger, int k, String[] keys) {
    Cursor c = new Cursor();
    for (int i = 0; i < keys.length; i++) {
      put(trigger, k, c, keys[i], i);
    }
    int[] ds = displacements(c.data);
    int slots = EmbeddingSupport.numSlots(c.data);
    String label = trigger == Trigger.MAX_PROBES ? "MAX_PROBES(" + k + ")" : trigger.name();
    System.out.printf(
        "%-12s %-12s %6d %6d %6.2f %6d %6.2f %6d %6d %6d%n",
        regime.name(),
        label,
        ds.length,
        slots,
        slots == 0 ? 0.0 : (double) ds.length / slots,
        c.grows,
        mean(ds),
        pct(ds, 0.90),
        pct(ds, 0.99),
        ds.length == 0 ? 0 : ds[ds.length - 1]);
  }
}
