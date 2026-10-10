package datadog.trace.agent.tooling.context.benchmark;

import static datadog.trace.agent.tooling.context.benchmark.ContextStoreBenchmarkSupport.installAgent;
import static datadog.trace.agent.tooling.context.benchmark.ContextStoreBenchmarkSupport.newKey;
import static datadog.trace.agent.tooling.context.benchmark.ContextStoreWorkloadInstrumentation.STORE_COUNT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import context.benchmark.workload.WorkloadKey;
import datadog.trace.agent.tooling.context.benchmark.ContextStoreWorkloadInstrumentation.Context;
import datadog.trace.bootstrap.ContextStore;
import java.util.Random;
import java.util.function.Function;
import org.openjdk.jmh.annotations.AuxCounters;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.infra.ThreadParams;
import org.openjdk.jmh.runner.IterationType;

/**
 * Compares map-per-store settings using common instrumentation lifecycles. Each store has one fixed
 * behaviour, selected by {@code scenario}:
 *
 * <ul>
 *   <li>{@code GC}: request-local keys are assigned, checked after 16 requests, then abandoned.
 *   <li>{@code CLEANUP}: the same lifecycle, with explicit removal at completion.
 *   <li>{@code REUSE}: a bounded pool of keys keeps its state across requests. Subsequent uses
 *       check the state, then revisit it four times, recreating missing values.
 *   <li>{@code MIXED}: stores alternate between GC, cleanup and reuse. {@code CHURN_HEAVY} assigns
 *       three GC stores, two cleanup stores and one reuse store per group of six; {@code
 *       REUSE_HEAVY} assigns four reuse stores, one GC store and one cleanup store per group of
 *       six, with fallback store 14 on reuse and store 15 on GC.
 *   <li>{@code RECOVERY}: a diagnostic lifecycle which removes fallback associations before four
 *       revisits, then removes all associations at completion. One recreation should suffice.
 * </ul>
 *
 * <p>16 stores have Zipf popularity. {@code fallbackPercent} controls the fraction of fallback
 * traffic. {@code fallbackMode=DISTRIBUTED} spreads it across eligible stores; {@code CONCENTRATED}
 * confines it to stores 14-15, so those stores' behaviours determine the fallback mix. Half of the
 * traffic also visits a second store, which can have a different behaviour. Request-local stores
 * share the new key; reuse stores select their own pooled key. Initial assignment uses {@code put}
 * (50%), {@code getOrPut} (25%) or {@code getOrCompute} (25%). Values are fresh per key;
 * recreations also allocate fresh values. Reuse stores have up to 64 keys per key kind per thread.
 *
 * <p>{@code *AssignmentMisses} counts contexts missing immediately after assignment. {@code
 * *YoungRetirementMisses} counts contexts missing at request completion; {@code *PooledReuseMisses}
 * counts contexts missing before a pooled key is reused. Divide by the respective {@code *Checks}
 * counter for a miss rate. {@code *RevisitRecreates} counts recreation attempts, including rejected
 * associations. Reuse revisits follow pooled checks; recovery revisits follow young retirement
 * checks. Both make four visits per checked association; other lifecycles make none. Iteration
 * teardown rejects injected misses or recreations, and requires one recreation per fallback
 * association in recovery. Compare timings only when successful work and retention are comparable:
 * full per-store maps can drop new associations and make that mode appear faster.
 *
 * <p>When analysing retention, always favour young state over existing pooled state. In production,
 * many older objects linger in the heap after tracing has finished; future tracing may replace
 * their values anyway. This benchmark actively revisits pooled state, giving its retention more
 * weight than such lingering objects warrant.
 *
 * <p>Retained state competes with abandoned entries in mixed runs. Finishing a span or consuming a
 * continuation does not necessarily remove its state from the store; reuse models that distinction.
 * Recovery is explicit invalidation, not a model of natural eviction.
 *
 * <p>Each benchmark runs in its own forks with the agent's stale-entry cleaners. {@code
 * requestWork} burns CPU per request to control churn. {@code baseline} follows the same key and
 * value lifecycles without stores; compare raw request timings between modes. The request ring must
 * be full, with at least {@code minFallbackRetirementChecks} fallback checks per measurement and
 * thread. Seed and coverage overrides are diagnostic parameters.
 *
 * <p>Stores are selected through the {@link ContextStore} interface, whereas instrumentation
 * normally uses a constant store. {@link ContextStoreBenchmark#constantStoreRequestLifecycle}
 * complements this workload with literal store IDs. GC frequency depends on store allocations; use
 * a fixed heap and compare collectors, JDKs, and one versus many threads.
 *
 * <pre>
 * ./gradlew :dd-java-agent:agent-tooling:jmhJar
 * java -jar dd-java-agent/agent-tooling/build/libs/agent-tooling-*-jmh.jar \
 *   ContextStoreWorkloadBenchmark -t 16 -prof gc -jvmArgsAppend "-Xmx512m -XX:+UseG1GC"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Warmup(iterations = 3, time = 1)
// spans several 1s stale-entry cleaner sweeps, so each iteration sees a similar amount of cleanup
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class ContextStoreWorkloadBenchmark {
  static final int INJECTED = 0;
  static final int PARTIAL = 1;
  static final int UNINJECTED = 2;

  static final int PUT = 0;
  static final int GET_OR_PUT = 1;
  static final int GET_OR_COMPUTE = 2;

  static final int KEYS_PER_REQUEST = 4;
  static final int IN_FLIGHT_KEYS = 16 * KEYS_PER_REQUEST; // keys stay in flight for 16 requests
  static final int SECOND_STORE_PERCENT = 50;
  static final int PUT_PERCENT = 50;
  static final int GET_OR_PUT_PERCENT = 25;
  static final int REVISIT_COUNT = 4;
  static final int PATTERN_SIZE = 65536;

  /** Stores selected exclusively for non-injected keys in CONCENTRATED mode. */
  static final int[] FALLBACK_STORES = {14, 15};

  static final Context CONTEXT = new Context(42);
  static final Function<Object, Object> COMPUTE_CONTEXT = key -> CONTEXT;

  /** Cumulative Zipf (s=1) weights, so lower store indices are more popular. */
  static final double[] ZIPF = zipf(STORE_COUNT);

  public enum FallbackMode {
    /** Fallback traffic is spread across eligible stores with Zipf popularity. */
    DISTRIBUTED,
    /** Fallback traffic is confined to stores 14-15; injected traffic uses the other stores. */
    CONCENTRATED
  }

  /**
   * Request propagation (Jetty), explicit handoff/cleanup (Reactive Streams), and retained state
   * (Netty/JMS), alone or mixed across stores. Recovery is a synthetic diagnostic.
   */
  public enum Scenario {
    GC,
    CLEANUP,
    REUSE,
    MIXED,
    CHURN_HEAVY,
    REUSE_HEAVY,
    RECOVERY;

    /** Returns the fixed lifecycle for a store. */
    Scenario forStore(int store) {
      switch (this) {
        case MIXED:
          return store % 3 == 0 ? GC : store % 3 == 1 ? CLEANUP : REUSE;
        case CHURN_HEAVY:
          return store % 6 < 3 ? GC : store % 6 < 5 ? CLEANUP : REUSE;
        case REUSE_HEAVY:
          if (store == FALLBACK_STORES[1]) {
            return GC;
          }
          return store % 6 < 4 ? REUSE : store % 6 == 4 ? GC : CLEANUP;
        default:
          return this;
      }
    }
  }

  /** Runs the selected store lifecycles with {@code map-per-store=true}. */
  @Benchmark
  public void requestMapPerStore(Workload workload, Misses misses, Blackhole blackhole) {
    run(workload, misses.request, misses, true, blackhole);
  }

  /** Same as {@link #requestMapPerStore} with {@code map-per-store=false}. */
  @Benchmark
  public void requestGlobalStore(Workload workload, Misses misses, Blackhole blackhole) {
    run(workload, misses.request, misses, true, blackhole);
  }

  /** Same workload as the request benchmarks without touching the stores. */
  @Benchmark
  public void baseline(Workload workload, Request request, Blackhole blackhole) {
    run(workload, request, null, false, blackhole);
  }

  private static void run(
      Workload workload, Request request, Misses misses, boolean useStores, Blackhole blackhole) {
    for (int k = 0; k < KEYS_PER_REQUEST; k++) {
      int p = request.nextPattern();
      int slot = request.nextSlot();
      WorkloadKey finished = request.inFlightKeys[slot];
      if (finished != null && useStores) {
        int q = request.inFlightPatterns[slot];
        request.finish(workload, finished, q, request.inFlightContexts[slot], misses, blackhole);
      }

      boolean pooledA = workload.behaviours[request.storesA[p]] == Scenario.REUSE;
      boolean pooledB =
          request.storesB[p] < 0 || workload.behaviours[request.storesB[p]] == Scenario.REUSE;
      WorkloadKey key = null;
      Object context = null;
      if (!pooledA || !pooledB) {
        key = workload.prototypes[request.kinds[p]].newKey();
        context = new Context(42);
        if (useStores) {
          request.assign(workload, key, p, context);
          misses.assigned(
              request.kinds[p],
              request.contextCount(workload, p),
              request.check(workload, key, p, context));
        }
      }
      request.reuse(workload, request.storesA[p], p, slot, useStores, misses, blackhole);
      if (request.storesB[p] >= 0) {
        request.reuse(workload, request.storesB[p], p, slot, useStores, misses, blackhole);
      }
      request.inFlightKeys[slot] = key;
      request.inFlightPatterns[slot] = p;
      request.inFlightContexts[slot] = context;
    }

    Blackhole.consumeCPU(workload.requestWork);
  }

  @State(Scope.Benchmark)
  public static class Workload {
    @Param("1")
    public long seed;

    @Param({"1", "10"})
    public int fallbackPercent;

    @Param({"DISTRIBUTED", "CONCENTRATED"})
    public FallbackMode fallbackMode;

    @Param({"GC", "CLEANUP", "REUSE", "MIXED"})
    public Scenario scenario;

    @Param("1000")
    public long requestWork;

    /** Minimum fallback completion/reuse checks per thread and measurement iteration. */
    @Param("100")
    public int minFallbackRetirementChecks;

    private final Scenario[] behaviours = new Scenario[STORE_COUNT];

    private final WorkloadKey[] prototypes = new WorkloadKey[3];

    @SuppressWarnings("unchecked")
    private final ContextStore<Object, Object>[] stores = new ContextStore[STORE_COUNT];

    @Setup(Level.Trial)
    @SuppressWarnings("unchecked")
    public void setUp(BenchmarkParams benchmarkParams) throws Exception {
      validateParameters();
      boolean mapPerStore = installAgent(requestedMode(benchmarkParams.getBenchmark()));

      prototypes[INJECTED] = newKey("context.benchmark.workload.InjectedKey", true);
      prototypes[PARTIAL] = newKey("context.benchmark.workload.PartialKey", true);
      prototypes[UNINJECTED] = newKey("context.benchmark.workload.UninjectedKey", false);

      for (int i = 0; i < STORE_COUNT; i++) {
        behaviours[i] = scenario.forStore(i);
        stores[i] = (ContextStore<Object, Object>) prototypes[INJECTED].store(i);
        if (stores[i] == null) {
          throw new IllegalStateException("context store " + i + " is not active");
        }
      }
      String expectedStore = mapPerStore ? "FieldBacked" : "Boxed";
      if (!stores[0].getClass().getSimpleName().startsWith(expectedStore)) {
        throw new IllegalStateException(
            "mapPerStore=" + mapPerStore + " but stores are " + stores[0].getClass().getName());
      }
      verifyStores();

      if (!schedulerRunning()) {
        throw new IllegalStateException("stale-entry cleaners are not scheduled");
      }
    }

    /** Returns the mode for the given request benchmark, or {@code null} for the baseline. */
    private static Boolean requestedMode(String benchmark) {
      if (benchmark.endsWith(".requestMapPerStore")) {
        return Boolean.TRUE;
      } else if (benchmark.endsWith(".requestGlobalStore")) {
        return Boolean.FALSE;
      } else if (benchmark.endsWith(".baseline")) {
        return null;
      }
      throw new IllegalArgumentException("unknown context-store workload benchmark: " + benchmark);
    }

    private void validateParameters() {
      requirePercentage("fallbackPercent", fallbackPercent);
      if (requestWork < 0) {
        throw new IllegalArgumentException("requestWork must not be negative");
      }
      if (minFallbackRetirementChecks < 0) {
        throw new IllegalArgumentException("minFallbackRetirementChecks must not be negative");
      }
    }

    /** Checks store isolation for every key type, including the partial-field redirect. */
    private void verifyStores() {
      Context[] contexts = new Context[STORE_COUNT];
      for (int i = 0; i < STORE_COUNT; i++) {
        contexts[i] = new Context(i);
      }
      for (int kind = INJECTED; kind <= UNINJECTED; kind++) {
        WorkloadKey key = prototypes[kind].newKey();
        for (int i = 0; i < STORE_COUNT; i++) {
          stores[i].put(key, contexts[i]);
        }
        for (int i = 0; i < STORE_COUNT; i++) {
          if (stores[i].get(key) != contexts[i]
              || stores[i].getOrPut(key, CONTEXT) != contexts[i]
              || stores[i].getOrCompute(key, COMPUTE_CONTEXT) != contexts[i]
              || stores[i].remove(key) != contexts[i]) {
            throw new IllegalStateException(
                "context store " + i + " is not isolated for " + key.getClass().getName());
          }
          for (int j = 0; j < STORE_COUNT; j++) {
            if (stores[j].get(key) != (j <= i ? null : contexts[j])) {
              throw new IllegalStateException(
                  "removing context store " + i + " affected store " + j);
            }
          }
        }
      }
    }
  }

  @State(Scope.Thread)
  public static class Request {
    // bytes rather than ints keep the per-thread patterns small, so they disturb the cache less
    private final byte[] kinds = new byte[PATTERN_SIZE];
    private final byte[] storesA = new byte[PATTERN_SIZE];
    private final byte[] storesB = new byte[PATTERN_SIZE];
    private final byte[] ops = new byte[PATTERN_SIZE];
    private final WorkloadKey[] inFlightKeys = new WorkloadKey[IN_FLIGHT_KEYS];
    private final int[] inFlightPatterns = new int[IN_FLIGHT_KEYS];
    private final Object[] inFlightContexts = new Object[IN_FLIGHT_KEYS];
    private Object assignmentContext;
    // Reused by this thread; getOrCompute invokes it synchronously, without a per-key lambda.
    private final Function<Object, Object> contextFactory = key -> assignmentContext;
    private final WorkloadKey[][][] pooledKeys = new WorkloadKey[STORE_COUNT][3][IN_FLIGHT_KEYS];
    private final Object[][][] pooledContexts = new Object[STORE_COUNT][3][IN_FLIGHT_KEYS];
    private long revisitRecreates;
    // Only revisit misses count as recreations.
    private final Function<Object, Object> revisitFactory = key -> recreateContext();
    private int patternIndex;
    private int slotIndex;
    private int youngOccupiedSlots;

    @Setup(Level.Trial)
    public void setUp(Workload workload, ThreadParams threadParams) {
      // spread seeds so different seeds don't reuse each other's per-thread patterns
      Random random = new Random(workload.seed * 1_000_003L + threadParams.getThreadIndex());
      boolean concentrated = workload.fallbackMode == FallbackMode.CONCENTRATED;
      int fallbackCount = (int) Math.round(PATTERN_SIZE * workload.fallbackPercent / 100.0);
      int partialCount = concentrated ? 0 : fallbackCount / 2;
      int[] counts = {PATTERN_SIZE - fallbackCount, partialCount, fallbackCount - partialCount};
      int offset = 0;
      for (int kind = INJECTED; kind <= UNINJECTED; kind++) {
        for (int i = 0; i < counts[kind]; i++) {
          kinds[offset++] = (byte) kind;
        }
      }
      // Shuffle key kinds to retain fallback coverage without clustering requests.
      for (int p = PATTERN_SIZE - 1; p > 0; p--) {
        int q = random.nextInt(p + 1);
        byte kind = kinds[p];
        kinds[p] = kinds[q];
        kinds[q] = kind;
      }
      for (int p = 0; p < PATTERN_SIZE; p++) {
        int kind = kinds[p];
        storesA[p] = (byte) pickStore(random, kind, concentrated);
        storesB[p] =
            (byte)
                (random.nextInt(100) < SECOND_STORE_PERCENT
                    ? pickOtherStore(random, kind, concentrated, storesA[p])
                    : -1);
        int op = random.nextInt(100);
        ops[p] =
            (byte)
                (op < PUT_PERCENT
                    ? PUT
                    : op < PUT_PERCENT + GET_OR_PUT_PERCENT ? GET_OR_PUT : GET_OR_COMPUTE);
      }
    }

    /** Assigns contexts to the request-local stores for pattern {@code p}. */
    void assign(Workload workload, WorkloadKey key, int p, Object context) {
      assignmentContext = context;
      if (workload.behaviours[storesA[p]] != Scenario.REUSE) {
        assign(workload.stores[storesA[p]], key, ops[p], context, contextFactory);
      }
      if (storesB[p] >= 0 && workload.behaviours[storesB[p]] != Scenario.REUSE) {
        assign(workload.stores[storesB[p]], key, ops[p], context, contextFactory);
      }
    }

    private static void assign(
        ContextStore<Object, Object> store,
        WorkloadKey key,
        int op,
        Object context,
        Function<Object, Object> factory) {
      switch (op) {
        case GET_OR_PUT:
          store.getOrPut(key, context);
          break;
        case GET_OR_COMPUTE:
          store.getOrCompute(key, factory);
          break;
        default:
          store.put(key, context);
      }
    }

    /** Returns how many request-local contexts for pattern {@code p} are missing. */
    int check(Workload workload, WorkloadKey key, int p, Object context) {
      int missing = 0;
      if (workload.behaviours[storesA[p]] != Scenario.REUSE) {
        missing += workload.stores[storesA[p]].get(key) != context ? 1 : 0;
      }
      if (storesB[p] >= 0 && workload.behaviours[storesB[p]] != Scenario.REUSE) {
        missing += workload.stores[storesB[p]].get(key) != context ? 1 : 0;
      }
      return missing;
    }

    int contextCount(Workload workload, int p) {
      return (workload.behaviours[storesA[p]] == Scenario.REUSE ? 0 : 1)
          + (storesB[p] < 0 || workload.behaviours[storesB[p]] == Scenario.REUSE ? 0 : 1);
    }

    /** Checks request-local associations before their keys leave flight. */
    void finish(
        Workload workload,
        WorkloadKey key,
        int p,
        Object context,
        Misses misses,
        Blackhole blackhole) {
      misses.retired(kinds[p], contextCount(workload, p), check(workload, key, p, context));
      finish(workload, storesA[p], key, p, misses, blackhole);
      if (storesB[p] >= 0) {
        finish(workload, storesB[p], key, p, misses, blackhole);
      }
    }

    private void finish(
        Workload workload, int index, WorkloadKey key, int p, Misses misses, Blackhole blackhole) {
      Scenario behaviour = workload.behaviours[index];
      ContextStore<Object, Object> store = workload.stores[index];
      if (behaviour == Scenario.RECOVERY) {
        if (kinds[p] != INJECTED) {
          store.remove(key);
        }
        revisit(store, key, kinds[p], misses, blackhole);
      }
      if (behaviour == Scenario.CLEANUP || behaviour == Scenario.RECOVERY) {
        store.remove(key);
      }
    }

    /** Uses a persistent key from the selected store's pool, repairing missing state. */
    void reuse(
        Workload workload,
        int index,
        int p,
        int slot,
        boolean useStores,
        Misses misses,
        Blackhole blackhole) {
      if (workload.behaviours[index] != Scenario.REUSE) {
        return;
      }
      int kind = kinds[p];
      WorkloadKey key = pooledKeys[index][kind][slot];
      Object context = pooledContexts[index][kind][slot];
      ContextStore<Object, Object> store = workload.stores[index];
      if (key == null) {
        key = workload.prototypes[kind].newKey();
        context = new Context(42);
        pooledKeys[index][kind][slot] = key;
        if (useStores) {
          assignmentContext = context;
          assign(store, key, ops[p], context, contextFactory);
          misses.assigned(kind, 1, store.get(key) != context ? 1 : 0);
        }
      } else if (useStores) {
        misses.reused(kind, store.get(key) != context ? 1 : 0);
        context = revisit(store, key, kind, misses, blackhole);
      }
      pooledContexts[index][kind][slot] = context;
    }

    /** Revisits an association and records any recreations. */
    private Object revisit(
        ContextStore<Object, Object> store,
        WorkloadKey key,
        int kind,
        Misses misses,
        Blackhole blackhole) {
      long before = revisitRecreates;
      Object context = null;
      for (int visit = 0; visit < REVISIT_COUNT; visit++) {
        context = revisit(store, key, (visit & 1) == 0);
        blackhole.consume(context);
      }
      misses.revisited(kind, revisitRecreates - before);
      return context;
    }

    /** Revisits an association, recreating its value only when missing. */
    private Object revisit(ContextStore<Object, Object> store, WorkloadKey key, boolean compute) {
      if (compute) {
        return store.getOrCompute(key, revisitFactory);
      }
      Object context = store.get(key);
      return store.getOrPut(key, context != null ? context : recreateContext());
    }

    private Object recreateContext() {
      revisitRecreates++;
      return new Context(42);
    }

    int nextPattern() {
      int p = patternIndex;
      patternIndex = (p + 1) & (PATTERN_SIZE - 1);
      return p;
    }

    int nextSlot() {
      int i = slotIndex;
      slotIndex = (i + 1) & (IN_FLIGHT_KEYS - 1);
      // Pooled-only traffic fills scheduling slots without adding request-local keys.
      if (youngOccupiedSlots < IN_FLIGHT_KEYS) {
        youngOccupiedSlots++;
      }
      return i;
    }
  }

  /**
   * Context checks and misses, split by whether the contexts are field-injected or fall back, and
   * by how recently they were assigned. Partially injected keys count as fallback because this
   * workload only selects stores for which they have no fields; see {@link #pickStore}.
   */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  public static class Misses {
    private Request request;
    private int minFallbackRetirementChecks;
    private boolean hasFallback;
    private boolean recovery;

    /** Request slots occupied at the end of the iteration, including pooled keys. */
    public long youngOccupiedSlots() {
      return request.youngOccupiedSlots;
    }

    /** Request slots available per thread. */
    public long youngCapacitySlots() {
      return request.inFlightKeys.length;
    }

    /** Injected contexts checked straight after being assigned. */
    public long injectedAssignmentChecks;

    /** Injected contexts missing straight after being assigned. */
    public long injectedAssignmentMisses;

    /** Young injected contexts checked at completion. */
    public long injectedYoungRetirementChecks;

    /** Young injected contexts missing at completion. */
    public long injectedYoungRetirementMisses;

    /** Pooled injected contexts checked before reuse. */
    public long injectedPooledReuseChecks;

    /** Pooled injected contexts missing before reuse. */
    public long injectedPooledReuseMisses;

    /** Injected contexts needing recreation during revisits. */
    public long injectedRevisitRecreates;

    /** Fallback contexts checked straight after being assigned. */
    public long fallbackAssignmentChecks;

    /** Fallback contexts missing straight after being assigned. */
    public long fallbackAssignmentMisses;

    /** Young fallback contexts checked at completion. */
    public long fallbackYoungRetirementChecks;

    /** Young fallback contexts missing at completion. */
    public long fallbackYoungRetirementMisses;

    /** Pooled fallback contexts checked before reuse. */
    public long fallbackPooledReuseChecks;

    /** Pooled fallback contexts missing before reuse. */
    public long fallbackPooledReuseMisses;

    /** Fallback contexts needing recreation during revisits. */
    public long fallbackRevisitRecreates;

    @Setup(Level.Iteration)
    public void reset(Request request, Workload workload) {
      this.request = request;
      minFallbackRetirementChecks = workload.minFallbackRetirementChecks;
      hasFallback = workload.fallbackPercent > 0;
      recovery = workload.scenario == Scenario.RECOVERY;
      injectedAssignmentChecks = 0;
      injectedAssignmentMisses = 0;
      injectedYoungRetirementChecks = 0;
      injectedYoungRetirementMisses = 0;
      injectedPooledReuseChecks = 0;
      injectedPooledReuseMisses = 0;
      injectedRevisitRecreates = 0;
      fallbackAssignmentChecks = 0;
      fallbackAssignmentMisses = 0;
      fallbackYoungRetirementChecks = 0;
      fallbackYoungRetirementMisses = 0;
      fallbackPooledReuseChecks = 0;
      fallbackPooledReuseMisses = 0;
      fallbackRevisitRecreates = 0;
    }

    @TearDown(Level.Iteration)
    public void verifyIteration(IterationParams iteration) {
      if (injectedAssignmentMisses != 0
          || injectedYoungRetirementMisses != 0
          || injectedPooledReuseMisses != 0
          || injectedRevisitRecreates != 0) {
        throw new IllegalStateException(
            "Injected state was lost: assignment misses="
                + injectedAssignmentMisses
                + ", young misses="
                + injectedYoungRetirementMisses
                + ", pooled misses="
                + injectedPooledReuseMisses
                + ", recreations="
                + injectedRevisitRecreates);
      }
      if (recovery && fallbackRevisitRecreates != fallbackYoungRetirementChecks) {
        throw new IllegalStateException(
            "Recovery requires one recreation per fallback association: checks="
                + fallbackYoungRetirementChecks
                + ", recreations="
                + fallbackRevisitRecreates);
      }
      if (iteration.getType() != IterationType.MEASUREMENT) {
        return;
      }
      if (request.youngOccupiedSlots != request.inFlightKeys.length) {
        throw new IllegalStateException("Request ring is not full; increase -wi/-w");
      }
      long fallbackChecks = fallbackYoungRetirementChecks + fallbackPooledReuseChecks;
      if (hasFallback && fallbackChecks < minFallbackRetirementChecks) {
        throw new IllegalStateException(
            "Only "
                + fallbackChecks
                + " fallback completion/reuse checks; require "
                + minFallbackRetirementChecks
                + " per thread. Increase -wi/-w or -r; use -p minFallbackRetirementChecks=0 for smoke tests.");
      }
    }

    void assigned(int kind, int checks, int missing) {
      if (kind != INJECTED) {
        fallbackAssignmentChecks += checks;
        fallbackAssignmentMisses += missing;
      } else {
        injectedAssignmentChecks += checks;
        injectedAssignmentMisses += missing;
      }
    }

    void retired(int kind, int checks, int missing) {
      if (kind != INJECTED) {
        fallbackYoungRetirementChecks += checks;
        fallbackYoungRetirementMisses += missing;
      } else {
        injectedYoungRetirementChecks += checks;
        injectedYoungRetirementMisses += missing;
      }
    }

    void reused(int kind, int missing) {
      if (kind != INJECTED) {
        fallbackPooledReuseChecks++;
        fallbackPooledReuseMisses += missing;
      } else {
        injectedPooledReuseChecks++;
        injectedPooledReuseMisses += missing;
      }
    }

    void revisited(int kind, long recreates) {
      if (kind != INJECTED) {
        fallbackRevisitRecreates += recreates;
      } else {
        injectedRevisitRecreates += recreates;
      }
    }
  }

  /**
   * Picks a store by popularity. In CONCENTRATED mode non-injected keys only use the fallback
   * stores and injected keys only use the others; otherwise partially injected keys only use stores
   * 8-15, which they have no fields for.
   */
  static int pickStore(Random random, int kind, boolean concentrated) {
    int store;
    do {
      double x = random.nextDouble();
      store = 0;
      while (ZIPF[store] < x) {
        store++;
      }
    } while (concentrated
        ? (kind == UNINJECTED) != isFallbackStore(store)
        : kind == PARTIAL && store < STORE_COUNT / 2);
    return store;
  }

  static boolean isFallbackStore(int store) {
    for (int fallbackStore : FALLBACK_STORES) {
      if (store == fallbackStore) {
        return true;
      }
    }
    return false;
  }

  static double[] zipf(int count) {
    double[] cumulative = new double[count];
    double total = 0;
    for (int i = 0; i < count; i++) {
      total += 1.0 / (i + 1);
      cumulative[i] = total;
    }
    for (int i = 0; i < count; i++) {
      cumulative[i] /= total;
    }
    cumulative[count - 1] = 1.0; // guard against rounding
    return cumulative;
  }

  static int pickOtherStore(Random random, int kind, boolean concentrated, int store) {
    int other;
    do {
      other = pickStore(random, kind, concentrated);
    } while (other == store);
    return other;
  }

  static boolean schedulerRunning() {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && thread.getName().equals("dd-task-scheduler")) {
        return true;
      }
    }
    return false;
  }

  private static void requirePercentage(String name, int value) {
    if (value < 0 || value > 100) {
      throw new IllegalArgumentException(name + " must be between 0 and 100");
    }
  }
}
