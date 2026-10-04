package datadog.trace.agent.tooling.context.benchmark;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import context.benchmark.ContextStoreBenchmarkKey;
import datadog.trace.agent.tooling.AgentInstaller;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.ByteBuddyAgent;
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
 * weak map.
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

  @State(Scope.Benchmark)
  public static class InstrumentedTypes {
    // static so the agent is only installed once per JVM, even when running with -f 0
    private static Class<?> fieldInjectedType;
    private static Class<?> nonFieldInjectedType;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
      synchronized (InstrumentedTypes.class) {
        if (fieldInjectedType != null) {
          return;
        }
        // must load before installing the agent: retransformation can't add fields to a class
        // that's already loaded, so this key type will fall back to the weak map
        nonFieldInjectedType = Class.forName("context.benchmark.NonFieldInjectedKey");

        Instrumentation instrumentation = ByteBuddyAgent.install();
        AgentInstaller.installBytebuddyAgent(instrumentation);

        // loaded after installing the agent, so this key type gets injected fields
        fieldInjectedType = Class.forName("context.benchmark.FieldInjectedKey");
      }
    }

    ContextStoreBenchmarkKey[] newFieldInjectedKeys(int count) throws Exception {
      ContextStoreBenchmarkKey[] keys = newKeys(fieldInjectedType, count);
      assertFieldInjection(keys[0], true);
      return keys;
    }

    ContextStoreBenchmarkKey[] newNonFieldInjectedKeys(int count) throws Exception {
      ContextStoreBenchmarkKey[] keys = newKeys(nonFieldInjectedType, count);
      assertFieldInjection(keys[0], false);
      return keys;
    }
  }

  @State(Scope.Thread)
  public static class Keys {
    @Param({"1", "1024"})
    public int keyCount;

    private ContextStoreBenchmarkKey[] fieldInjected;
    private ContextStoreBenchmarkKey[] nonFieldInjected;
    private final Object context = new ContextStoreBenchmarkInstrumentation.State(42);
    private int index;

    @Setup(Level.Trial)
    public void setUp(InstrumentedTypes types) throws Exception {
      assertPowerOfTwo("keyCount", keyCount);
      fieldInjected = types.newFieldInjectedKeys(keyCount);
      nonFieldInjected = types.newNonFieldInjectedKeys(keyCount);

      for (int i = 0; i < keyCount; i++) {
        fieldInjected[i].write(context);
        nonFieldInjected[i].write(context);
        if (fieldInjected[i].read() != 42 || nonFieldInjected[i].read() != 42) {
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

  private static ContextStoreBenchmarkKey[] newKeys(Class<?> type, int count) throws Exception {
    ContextStoreBenchmarkKey[] keys = new ContextStoreBenchmarkKey[count];
    for (int i = 0; i < count; i++) {
      keys[i] = (ContextStoreBenchmarkKey) type.getConstructor().newInstance();
    }
    return keys;
  }

  private static void assertPowerOfTwo(String name, int value) {
    if (Integer.bitCount(value) != 1) {
      throw new IllegalArgumentException(name + " must be a power of two");
    }
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
