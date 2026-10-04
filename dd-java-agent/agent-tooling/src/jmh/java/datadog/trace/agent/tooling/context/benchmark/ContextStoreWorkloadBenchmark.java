package datadog.trace.agent.tooling.context.benchmark;

import static datadog.trace.agent.tooling.context.benchmark.ContextStoreWorkloadInstrumentation.STORE_COUNT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import context.benchmark.workload.WorkloadKey;
import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.agent.tooling.context.benchmark.ContextStoreWorkloadInstrumentation.Context;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.ContextStores;
import datadog.trace.bootstrap.ObjectStoreCleaner;
import java.lang.instrument.Instrumentation;
import java.util.Random;
import java.util.function.Function;
import net.bytebuddy.agent.ByteBuddyAgent;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Compares {@code map-per-store=true} against {@code false} under a mixed workload:
 *
 * <ul>
 *   <li>16 stores with skewed (Zipf) popularity, so a few stores take most of the traffic
 *   <li>mostly field-injected keys with a configurable share of fallback keys, either spread at
 *       random across stores or, with {@code fallbackMode=BY_STORE}, concentrated in the stores
 *       whose key type could not be field-injected
 *   <li>contexts assigned by {@code put} (50%), {@code getOrPut} (25%), or {@code getOrCompute}
 *       (25%), with half of the keys associated with a second store
 *   <li>keys in flight for 16 requests, except 1% of slow keys in flight for {@code slowLifetime}
 *       requests, before being removed explicitly or abandoned to GC
 * </ul>
 *
 * <p>Each {@code mapPerStore} value runs in its own fork, with the stale-entry cleaners scheduled
 * as they are by {@code Agent.start}. {@code requestWork} burns CPU per request to bring the
 * request rate (and so the rate of abandoned keys) down to realistic levels. The {@code
 * assignmentMisses} counter reports contexts missing straight after being assigned; {@code
 * retirementMisses} reports older contexts missing when their key leaves flight. The corresponding
 * check counters provide denominators for comparing failure rates. Misses are possible under heavy
 * churn when a fallback store reaches its capacity or evicts an entry. The {@code baseline}
 * benchmark runs the same workload without touching the stores, providing an estimate of the
 * non-store overhead; compare the raw {@code request} results between modes rather than treating a
 * subtraction of independently measured point estimates as exact.
 *
 * <p>Run single and multi-threaded to compare contention on the shared shards:
 *
 * <pre>
 * ./gradlew :dd-java-agent:agent-tooling:jmh -Pjmh.includes=ContextStoreWorkloadBenchmark -Pjmh.threads=1
 * ./gradlew :dd-java-agent:agent-tooling:jmh -Pjmh.includes=ContextStoreWorkloadBenchmark -Pjmh.threads=4
 * </pre>
 *
 * <p>To select parameters or pass JVM options, build the jar with {@code ./gradlew
 * :dd-java-agent:agent-tooling:jmhJar} and run it directly. Run more threads than cores, with
 * realistic request work, so threads are also descheduled while using the shared shards:
 *
 * <pre>
 * java -jar dd-java-agent/agent-tooling/build/libs/agent-tooling-*-jmh.jar \
 *   ContextStoreWorkloadBenchmark -t 16
 * </pre>
 *
 * <p>Override with {@code -p requestWork=0} for a saturation test. The default matrix keeps the
 * realistic request-work scenario to avoid doubling every parameter combination.
 *
 * <p>GC frequency decides when abandoned keys become stale, which affects both the per-store size
 * cap and shard ageing, so also compare collectors with a fixed heap, and other JDKs:
 *
 * <pre>
 * java -jar dd-java-agent/agent-tooling/build/libs/agent-tooling-*-jmh.jar \
 *   ContextStoreWorkloadBenchmark -prof gc \
 *   -jvmArgsAppend "-Xmx512m -XX:+UseParallelGC"
 * java -jar dd-java-agent/agent-tooling/build/libs/agent-tooling-*-jmh.jar \
 *   ContextStoreWorkloadBenchmark -prof gc \
 *   -jvmArgsAppend "-Xmx512m -XX:+UseG1GC"
 * ./gradlew :dd-java-agent:agent-tooling:jmh -Pjmh.includes=ContextStoreWorkloadBenchmark -PtestJvm=8
 * ./gradlew :dd-java-agent:agent-tooling:jmh -Pjmh.includes=ContextStoreWorkloadBenchmark -PtestJvm=21
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
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
  static final int SLOW_PERCENT = 1;
  static final int SECOND_STORE_PERCENT = 50;
  static final int PUT_PERCENT = 50;
  static final int GET_OR_PUT_PERCENT = 25;
  static final int PATTERN_SIZE = 4096;

  /** Stores whose key type was loaded before the agent, so every key falls back (BY_STORE mode). */
  static final int[] FALLBACK_STORES = {14, 15};

  static final Context CONTEXT = new Context(42);
  static final Function<Object, Object> COMPUTE_CONTEXT = key -> CONTEXT;

  /** Cumulative Zipf (s=1) weights, so lower store indices are more popular. */
  static final double[] ZIPF = zipf(STORE_COUNT);

  public enum FallbackMode {
    RANDOM,
    BY_STORE
  }

  /**
   * Models one request: new keys get contexts, and keys from earlier requests leave flight after a
   * final check of their contexts.
   */
  @Benchmark
  public void request(Workload workload, Request request, Misses misses) {
    run(workload, request, misses, true);
  }

  /** Same workload as {@link #request} without touching the stores. */
  @Benchmark
  public void baseline(Workload workload, Request request, Misses misses) {
    run(workload, request, misses, false);
  }

  private static void run(Workload workload, Request request, Misses misses, boolean useStores) {
    ContextStore<Object, Object>[] stores = workload.stores;
    for (int k = 0; k < KEYS_PER_REQUEST; k++) {
      int p = request.nextPattern();
      WorkloadKey key = workload.prototypes[request.kinds[p]].newKey();
      if (useStores) {
        request.assign(stores, key, p);
        misses.assignmentChecks += request.contextCount(p);
        misses.assignmentMisses += request.check(stores, key, p);
      }

      WorkloadKey[] inFlightKeys;
      int[] inFlightPatterns;
      int slot;
      if (request.slow[p]) {
        inFlightKeys = request.slowKeys;
        inFlightPatterns = request.slowPatterns;
        slot = request.nextSlowSlot();
      } else {
        inFlightKeys = request.inFlightKeys;
        inFlightPatterns = request.inFlightPatterns;
        slot = request.nextSlot();
      }

      WorkloadKey finished = inFlightKeys[slot];
      if (finished != null && useStores) {
        int q = inFlightPatterns[slot];
        misses.retirementChecks += request.contextCount(q);
        misses.retirementMisses += request.check(stores, finished, q);
        if (request.removes[q]) {
          request.remove(stores, finished, q);
        } // otherwise the key is abandoned and left for the stale-entry cleaners
      }
      inFlightKeys[slot] = key;
      inFlightPatterns[slot] = p;
    }

    Blackhole.consumeCPU(workload.requestWork);
  }

  @State(Scope.Benchmark)
  public static class Workload {
    @Param({"true", "false"})
    public boolean mapPerStore;

    @Param({"1", "10"})
    public int fallbackPercent;

    @Param({"RANDOM", "BY_STORE"})
    public FallbackMode fallbackMode;

    @Param("50")
    public int removePercent;

    @Param("1000")
    public long requestWork;

    @Param("100000")
    public int slowLifetime;

    // static so the agent is only installed once per JVM, even when running with -f 0
    private static Boolean installedMode;

    private final WorkloadKey[] prototypes = new WorkloadKey[3];

    @SuppressWarnings("unchecked")
    private final ContextStore<Object, Object>[] stores = new ContextStore[STORE_COUNT];

    @Setup(Level.Trial)
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
      validateParameters();
      installAgent();

      prototypes[INJECTED] = newKey("context.benchmark.workload.InjectedKey");
      prototypes[PARTIAL] = newKey("context.benchmark.workload.PartialKey");
      prototypes[UNINJECTED] = newKey("context.benchmark.workload.UninjectedKey");
      assertFieldInjection(prototypes[INJECTED], true);
      assertFieldInjection(prototypes[PARTIAL], true);
      assertFieldInjection(prototypes[UNINJECTED], false);

      for (int i = 0; i < STORE_COUNT; i++) {
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

    private void installAgent() throws Exception {
      synchronized (Workload.class) {
        if (installedMode != null) {
          if (installedMode.booleanValue() != mapPerStore) {
            throw new IllegalStateException(
                "mapPerStore=" + installedMode + " is already installed; run with forks");
          }
          return;
        }
        // must be set before AgentInstaller or ContextStores read the config
        System.setProperty("dd.trace.runtime.context.map-per-store", Boolean.toString(mapPerStore));

        // must load before installing the agent: retransformation can't add fields to a class
        // that's already loaded, so this key type will fall back to the weak map
        Class.forName("context.benchmark.workload.UninjectedKey");

        Instrumentation instrumentation = ByteBuddyAgent.install();
        AgentInstaller.installBytebuddyAgent(instrumentation);
        // scheduled by Agent.start in production; a no-op when mapPerStore=true
        ObjectStoreCleaner.schedule();

        if (ContextStores.STORE_DESCRIPTOR.contains("Boxed") == mapPerStore) {
          throw new IllegalStateException(
              "mapPerStore=" + mapPerStore + " but " + ContextStores.STORE_DESCRIPTOR);
        }
        installedMode = mapPerStore;
      }
    }

    private void validateParameters() {
      requirePercentage("fallbackPercent", fallbackPercent);
      requirePercentage("removePercent", removePercent);
      if (requestWork < 0) {
        throw new IllegalArgumentException("requestWork must not be negative");
      }
      if (slowLifetime <= 0) {
        throw new IllegalArgumentException("slowLifetime must be positive");
      }
    }

    /** Checks each store works for every key type, including the partial-field redirect. */
    private void verifyStores() {
      Context context = new Context(42);
      for (int kind = INJECTED; kind <= UNINJECTED; kind++) {
        for (int i = 0; i < STORE_COUNT; i++) {
          WorkloadKey key = prototypes[kind].newKey();
          stores[i].put(key, context);
          if (stores[i].get(key) != context || stores[i].remove(key) != context) {
            throw new IllegalStateException(
                "context store " + i + " is not active for " + key.getClass().getName());
          }
        }
      }
    }
  }

  @State(Scope.Thread)
  public static class Request {
    private final int[] kinds = new int[PATTERN_SIZE];
    private final int[] storesA = new int[PATTERN_SIZE];
    private final int[] storesB = new int[PATTERN_SIZE];
    private final boolean[] removes = new boolean[PATTERN_SIZE];
    private final int[] ops = new int[PATTERN_SIZE];
    private final boolean[] slow = new boolean[PATTERN_SIZE];
    private final WorkloadKey[] inFlightKeys = new WorkloadKey[IN_FLIGHT_KEYS];
    private final int[] inFlightPatterns = new int[IN_FLIGHT_KEYS];
    private WorkloadKey[] slowKeys;
    private int[] slowPatterns;
    private int patternIndex;
    private int slotIndex;
    private int slowSlotIndex;

    @Setup(Level.Trial)
    public void setUp(Workload workload, ThreadParams threadParams) {
      Random random = new Random(threadParams.getThreadIndex() + 1);
      for (int p = 0; p < PATTERN_SIZE; p++) {
        boolean byStore = workload.fallbackMode == FallbackMode.BY_STORE;
        int kind = pickKind(random, workload.fallbackPercent, byStore);
        kinds[p] = kind;
        storesA[p] = pickStore(random, kind, byStore);
        storesB[p] =
            random.nextInt(100) < SECOND_STORE_PERCENT
                ? pickOtherStore(random, kind, byStore, storesA[p])
                : -1;
        removes[p] = random.nextInt(100) < workload.removePercent;
        int op = random.nextInt(100);
        ops[p] =
            op < PUT_PERCENT
                ? PUT
                : op < PUT_PERCENT + GET_OR_PUT_PERCENT ? GET_OR_PUT : GET_OR_COMPUTE;
        slow[p] = random.nextInt(100) < SLOW_PERCENT;
      }
      // sized so slow keys stay in flight for slowLifetime requests on average
      int slowKeyCount =
          Math.max(1, (int) ((long) workload.slowLifetime * KEYS_PER_REQUEST * SLOW_PERCENT / 100));
      slowKeys = new WorkloadKey[slowKeyCount];
      slowPatterns = new int[slowKeyCount];
    }

    /** Assigns the context to the key's stores for pattern {@code p}. */
    void assign(ContextStore<Object, Object>[] stores, WorkloadKey key, int p) {
      assign(stores[storesA[p]], key, ops[p]);
      if (storesB[p] >= 0) {
        assign(stores[storesB[p]], key, ops[p]);
      }
    }

    private static void assign(ContextStore<Object, Object> store, WorkloadKey key, int op) {
      switch (op) {
        case GET_OR_PUT:
          store.getOrPut(key, CONTEXT);
          break;
        case GET_OR_COMPUTE:
          store.getOrCompute(key, COMPUTE_CONTEXT);
          break;
        default:
          store.put(key, CONTEXT);
      }
    }

    /** Returns how many of the key's contexts for pattern {@code p} have gone missing. */
    int check(ContextStore<Object, Object>[] stores, WorkloadKey key, int p) {
      int missing = stores[storesA[p]].get(key) != CONTEXT ? 1 : 0;
      if (storesB[p] >= 0 && stores[storesB[p]].get(key) != CONTEXT) {
        missing++;
      }
      return missing;
    }

    int contextCount(int p) {
      return storesB[p] >= 0 ? 2 : 1;
    }

    /** Removes the key's contexts for pattern {@code p}. */
    void remove(ContextStore<Object, Object>[] stores, WorkloadKey key, int p) {
      stores[storesA[p]].remove(key);
      if (storesB[p] >= 0) {
        stores[storesB[p]].remove(key);
      }
    }

    int nextPattern() {
      int p = patternIndex;
      patternIndex = (p + 1) & (PATTERN_SIZE - 1);
      return p;
    }

    int nextSlot() {
      int i = slotIndex;
      slotIndex = (i + 1) & (IN_FLIGHT_KEYS - 1);
      return i;
    }

    int nextSlowSlot() {
      int i = slowSlotIndex;
      slowSlotIndex = i + 1 < slowKeys.length ? i + 1 : 0;
      return i;
    }
  }

  /** Context checks and misses, split by how recently the contexts were assigned. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  public static class Misses {
    /** Contexts checked straight after being assigned. */
    public long assignmentChecks;

    /** Contexts missing straight after being assigned. */
    public long assignmentMisses;

    /** Contexts checked when their key leaves flight. */
    public long retirementChecks;

    /** Contexts missing when their key leaves flight. */
    public long retirementMisses;

    @Setup(Level.Iteration)
    public void reset() {
      assignmentChecks = 0;
      assignmentMisses = 0;
      retirementChecks = 0;
      retirementMisses = 0;
    }
  }

  /**
   * Picks the key kind. In BY_STORE mode fallback keys are non-injected keys for the fallback
   * stores; otherwise the fallback share is split evenly between partially-injected and
   * non-injected keys.
   */
  static int pickKind(Random random, int fallbackPercent, boolean byStore) {
    if (byStore) {
      return random.nextInt(100) < fallbackPercent ? UNINJECTED : INJECTED;
    }
    int x = random.nextInt(200);
    if (x < fallbackPercent) {
      return PARTIAL;
    } else if (x < 2 * fallbackPercent) {
      return UNINJECTED;
    } else {
      return INJECTED;
    }
  }

  /**
   * Picks a store by popularity. In BY_STORE mode non-injected keys only use the fallback stores and
   * injected keys only use the others; otherwise partially-injected keys only use stores 8-15,
   * which they have no fields for.
   */
  static int pickStore(Random random, int kind, boolean byStore) {
    int store;
    do {
      double x = random.nextDouble();
      store = 0;
      while (ZIPF[store] < x) {
        store++;
      }
    } while (byStore
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

  static int pickOtherStore(Random random, int kind, boolean byStore, int store) {
    int other;
    do {
      other = pickStore(random, kind, byStore);
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

  private static WorkloadKey newKey(String typeName) throws Exception {
    return (WorkloadKey) Class.forName(typeName).getConstructor().newInstance();
  }

  private static void assertFieldInjection(Object key, boolean expected) {
    boolean injected = false;
    for (Class<?> type : key.getClass().getInterfaces()) {
      injected |= type.getName().equals("datadog.instrument.fieldinject.KeyWithValue");
    }
    if (injected != expected) {
      throw new IllegalStateException(
          key.getClass().getName()
              + " field injection: expected "
              + expected
              + ", got "
              + injected);
    }
  }
}
