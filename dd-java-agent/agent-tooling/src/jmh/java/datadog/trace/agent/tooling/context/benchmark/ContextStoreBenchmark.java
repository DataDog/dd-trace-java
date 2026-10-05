package datadog.trace.agent.tooling.context.benchmark;

import static datadog.trace.agent.tooling.context.benchmark.ContextStoreBenchmarkSupport.installAgent;
import static datadog.trace.agent.tooling.context.benchmark.ContextStoreBenchmarkSupport.newKey;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import context.benchmark.ContextStoreBenchmarkKey;
import java.util.function.Function;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Compares context-store access for keys with injected fields against keys that fall back to the
 * store's map, with {@code map-per-store} both enabled and disabled.
 *
 * <p>The advice uses the instrumented {@code this} as the context key, so once the store access is
 * inlined C2 knows the exact key type and binds the injected accessor statically. Results therefore
 * don't depend on how many other key types have been seen by the shared store code.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ContextStoreBenchmark {
  @Benchmark
  public int fieldInjectedRead(Keys keys) {
    return keys.fieldInjected[keys.next()].read();
  }

  @Benchmark
  public int nonFieldInjectedRead(Keys keys) {
    return keys.nonFieldInjected[keys.next()].read();
  }

  @Benchmark
  @OperationsPerInvocation(2)
  public int mixedRead(Keys keys) {
    int i = keys.next();
    return keys.fieldInjected[i].read() + keys.nonFieldInjected[i].read();
  }

  @Benchmark
  public void fieldInjectedWrite(Keys keys) {
    keys.fieldInjected[keys.next()].write(keys.context);
  }

  @Benchmark
  public void nonFieldInjectedWrite(Keys keys) {
    keys.nonFieldInjected[keys.next()].write(keys.context);
  }

  @Benchmark
  @OperationsPerInvocation(2)
  public void mixedWrite(Keys keys) {
    int i = keys.next();
    keys.fieldInjected[i].write(keys.context);
    keys.nonFieldInjected[i].write(keys.context);
  }

  /** Reads an existing context without replacing it with the supplied value. */
  @Benchmark
  public Object fieldInjectedGetOrPutHit(Keys keys) {
    return keys.fieldInjected[keys.next()].getOrPutContext(0, keys.alternateContext);
  }

  @Benchmark
  public Object nonFieldInjectedGetOrPutHit(Keys keys) {
    return keys.nonFieldInjected[keys.next()].getOrPutContext(0, keys.alternateContext);
  }

  /** Reads an existing context without invoking the factory. */
  @Benchmark
  public Object fieldInjectedGetOrComputeHit(Keys keys) {
    return keys.fieldInjected[keys.next()].getOrComputeContext(0, keys.contextFactory);
  }

  @Benchmark
  public Object nonFieldInjectedGetOrComputeHit(Keys keys) {
    return keys.nonFieldInjected[keys.next()].getOrComputeContext(0, keys.contextFactory);
  }

  /** Models request-local context association without making application keys shared by threads. */
  @Benchmark
  public boolean mixedRequestLifecycle(LifecycleKeys keys) {
    int i = keys.next();
    int storeIndex = keys.storeIndex(i);
    ContextStoreBenchmarkKey fieldInjected = keys.fieldInjected[i];
    ContextStoreBenchmarkKey nonFieldInjected = keys.nonFieldInjected[i];
    Object context = keys.contexts[storeIndex];

    fieldInjected.putContext(storeIndex, context);
    nonFieldInjected.putContext(storeIndex, context);

    boolean observed = true;
    for (int j = 0; j < 4; j++) {
      observed &= fieldInjected.getContext(storeIndex) == context;
      observed &= nonFieldInjected.getContext(storeIndex) == context;
    }

    observed &= fieldInjected.removeContext(storeIndex) == context;
    observed &= nonFieldInjected.removeContext(storeIndex) == context;
    return observed;
  }

  /**
   * A companion to the churn workload with literal store IDs in instrumented key methods. Each
   * invocation allocates fresh keys, assigns two contexts, reads them, and removes them. This
   * isolates request-local access; it does not model abandoned keys or delayed retirement. Use
   * {@code -p freshContexts=true} to allocate contexts as well as keys.
   */
  @Benchmark
  public boolean constantStoreRequestLifecycle(FreshKeys keys) {
    ContextStoreBenchmarkKey injected = keys.fieldInjected.newKey();
    ContextStoreBenchmarkKey fallback = keys.nonFieldInjected.newKey();
    Object context =
        keys.freshContexts ? new ContextStoreBenchmarkInstrumentation.State(42) : keys.context;
    Object secondContext =
        keys.freshContexts
            ? new ContextStoreBenchmarkInstrumentation.State1(42)
            : keys.secondContext;
    injected.putContext(0, context);
    fallback.putContext(0, context);
    injected.putContext(1, secondContext);
    fallback.putContext(1, secondContext);
    boolean observed = true;
    for (int i = 0; i < 4; i++) {
      observed &= injected.getContext(0) == context;
      observed &= fallback.getContext(0) == context;
      observed &= injected.getContext(1) == secondContext;
      observed &= fallback.getContext(1) == secondContext;
    }
    observed &= injected.removeContext(0) == context;
    observed &= fallback.removeContext(0) == context;
    observed &= injected.removeContext(1) == secondContext;
    observed &= fallback.removeContext(1) == secondContext;
    return observed;
  }

  @State(Scope.Thread)
  public static class FreshKeys {
    @Param("false")
    public boolean freshContexts;

    private ContextStoreBenchmarkKey fieldInjected;
    private ContextStoreBenchmarkKey nonFieldInjected;
    private final Object context = new ContextStoreBenchmarkInstrumentation.State(42);
    private final Object secondContext = new ContextStoreBenchmarkInstrumentation.State1(42);

    @Setup(Level.Trial)
    public void setUp(InstrumentedTypes types) throws Exception {
      fieldInjected = types.newFieldInjectedKeys(1)[0];
      nonFieldInjected = types.newNonFieldInjectedKeys(1)[0];
      if (!new ContextStoreBenchmark().constantStoreRequestLifecycle(this)) {
        throw new IllegalStateException("constant-store lifecycle instrumentation is not active");
      }
    }
  }

  @State(Scope.Benchmark)
  public static class InstrumentedTypes {
    @Param({"true", "false"})
    public boolean mapPerStore;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
      installAgent(mapPerStore);
    }

    ContextStoreBenchmarkKey[] newFieldInjectedKeys(int count) throws Exception {
      return newKeys("context.benchmark.FieldInjectedKey", count, true);
    }

    ContextStoreBenchmarkKey[] newNonFieldInjectedKeys(int count) throws Exception {
      return newKeys("context.benchmark.NonFieldInjectedKey", count, false);
    }
  }

  @State(Scope.Thread)
  public static class Keys {
    @Param({"1", "1024"})
    public int keyCount;

    private ContextStoreBenchmarkKey[] fieldInjected;
    private ContextStoreBenchmarkKey[] nonFieldInjected;
    private final Object context = new ContextStoreBenchmarkInstrumentation.State(42);
    private final Object alternateContext = new ContextStoreBenchmarkInstrumentation.State(-1);
    private final Function<Object, Object> contextFactory = key -> alternateContext;
    private int index;

    @Setup(Level.Trial)
    public void setUp(InstrumentedTypes types) throws Exception {
      assertPowerOfTwo("keyCount", keyCount);
      fieldInjected = types.newFieldInjectedKeys(keyCount);
      nonFieldInjected = types.newNonFieldInjectedKeys(keyCount);

      Function<Object, Object> unexpectedFactory =
          key -> {
            throw new IllegalStateException("getOrCompute invoked the factory for a populated key");
          };
      for (int i = 0; i < keyCount; i++) {
        fieldInjected[i].write(context);
        nonFieldInjected[i].write(context);
        if (fieldInjected[i].read() != 42
            || nonFieldInjected[i].read() != 42
            || fieldInjected[i].getOrPutContext(0, alternateContext) != context
            || nonFieldInjected[i].getOrPutContext(0, alternateContext) != context
            || fieldInjected[i].getOrComputeContext(0, unexpectedFactory) != context
            || nonFieldInjected[i].getOrComputeContext(0, unexpectedFactory) != context) {
          throw new IllegalStateException("context-store instrumentation is not active");
        }
      }
    }

    /** Returns the current key index and advances to the next key. */
    int next() {
      int i = index;
      index = (i + 1) & (keyCount - 1);
      return i;
    }
  }

  @State(Scope.Thread)
  public static class LifecycleKeys {
    @Param("1024")
    public int keyCount;

    @Param({"1", "4"})
    public int storeCount;

    private ContextStoreBenchmarkKey[] fieldInjected;
    private ContextStoreBenchmarkKey[] nonFieldInjected;
    private Object[] contexts;
    private int index;
    private int threadIndex;

    @Setup(Level.Trial)
    public void setUp(InstrumentedTypes types, ThreadParams threadParams) throws Exception {
      assertPowerOfTwo("keyCount", keyCount);
      assertPowerOfTwo("storeCount", storeCount);
      if (storeCount > 4) {
        throw new IllegalArgumentException("storeCount must be 1, 2, or 4");
      }
      fieldInjected = types.newFieldInjectedKeys(keyCount);
      nonFieldInjected = types.newNonFieldInjectedKeys(keyCount);
      threadIndex = threadParams.getThreadIndex();
      contexts =
          new Object[] {
            new ContextStoreBenchmarkInstrumentation.State(42),
            new ContextStoreBenchmarkInstrumentation.State1(42),
            new ContextStoreBenchmarkInstrumentation.State2(42),
            new ContextStoreBenchmarkInstrumentation.State3(42)
          };
      verifyStores();
    }

    private void verifyStores() {
      for (int storeIndex = 0; storeIndex < storeCount; storeIndex++) {
        Object context = contexts[storeIndex];
        fieldInjected[0].putContext(storeIndex, context);
        nonFieldInjected[0].putContext(storeIndex, context);
        if (fieldInjected[0].getContext(storeIndex) != context
            || nonFieldInjected[0].getContext(storeIndex) != context
            || fieldInjected[0].removeContext(storeIndex) != context
            || nonFieldInjected[0].removeContext(storeIndex) != context) {
          throw new IllegalStateException("context store " + storeIndex + " is not active");
        }
      }
    }

    /** Returns the current key index and advances to the next key. */
    int next() {
      int i = index;
      index = (i + 1) & (keyCount - 1);
      return i;
    }

    /**
     * Rotates through the stores, offset by thread so concurrent threads don't all hit the same
     * store in lock-step.
     */
    int storeIndex(int keyIndex) {
      return (keyIndex + threadIndex) & (storeCount - 1);
    }
  }

  private static ContextStoreBenchmarkKey[] newKeys(String typeName, int count, boolean injected)
      throws Exception {
    ContextStoreBenchmarkKey[] keys = new ContextStoreBenchmarkKey[count];
    for (int i = 0; i < count; i++) {
      keys[i] = newKey(typeName, injected);
    }
    return keys;
  }

  private static void assertPowerOfTwo(String name, int value) {
    if (Integer.bitCount(value) != 1) {
      throw new IllegalArgumentException(name + " must be a power of two");
    }
  }
}
