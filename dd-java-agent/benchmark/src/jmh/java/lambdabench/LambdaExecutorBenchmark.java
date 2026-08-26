package lambdabench;

import datadog.trace.api.Trace;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Measures the executor task-submission path for a lambda {@code Runnable}. Compare the three
 * nested fork variants (run with {@code -prof gc}):
 *
 * <ul>
 *   <li>{@link NoAgent} — baseline, no agent.
 *   <li>{@link AgentLambdaOff} — agent on, lambda metafactory instrumentation OFF: the lambda is
 *       wrapped on every submit (allocates a {@code Wrapper}).
 *   <li>{@link AgentLambdaOn} — agent on, lambda metafactory instrumentation ON: the lambda is
 *       field-injected, so no wrapper is allocated and identity is preserved.
 * </ul>
 *
 * <p>The headline metric is {@code ·gc.alloc.rate.norm} (bytes/op): the delta between {@code
 * AgentLambdaOff} and {@code AgentLambdaOn} on {@link #submitLambda} is the wrapper allocation we
 * removed. Lives outside the {@code datadog.*} package on purpose — {@code LambdaTransformerHelper}
 * skips agent-owned lambdas, and a real application's lambdas are not under {@code datadog.*}.
 */
public abstract class LambdaExecutorBenchmark {

  // Relative to the JMH working directory, which Gradle sets to this project's directory.
  private static final String AGENT = "-javaagent:build/agent/dd-java-agent.jar";

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodHandle RUNNABLE_TARGET;
  private static final MethodHandle SUPPLIER_TARGET;

  static {
    try {
      RUNNABLE_TARGET =
          LOOKUP.findStatic(
              LambdaExecutorBenchmark.class, "runTarget", MethodType.methodType(void.class));
      SUPPLIER_TARGET =
          LOOKUP.findStatic(
              LambdaExecutorBenchmark.class, "supplyTarget", MethodType.methodType(Object.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @State(Scope.Benchmark)
  public static class ExecutorState {
    ExecutorService pool;

    @Setup
    public void setup() {
      pool = Executors.newSingleThreadExecutor();
    }

    @TearDown
    public void tearDown() {
      pool.shutdownNow();
    }
  }

  /** Submit a lambda Runnable to the executor under an active trace, and wait for it to run. */
  @Benchmark
  public void submitLambda(ExecutorState state) throws InterruptedException {
    runUnderTrace(state.pool);
  }

  /** Measures cold linkage of an eligible {@link Runnable} lambda class. */
  @Benchmark
  public CallSite linkRunnableLambda() throws LambdaConversionException {
    return LambdaMetafactory.metafactory(
        LOOKUP,
        "run",
        MethodType.methodType(Runnable.class),
        MethodType.methodType(void.class),
        RUNNABLE_TARGET,
        MethodType.methodType(void.class));
  }

  /** Measures cold linkage of a non-task lambda, which should bypass the agent transformer. */
  @Benchmark
  public CallSite linkSupplierLambda() throws LambdaConversionException {
    return LambdaMetafactory.metafactory(
        LOOKUP,
        "get",
        MethodType.methodType(Supplier.class),
        MethodType.methodType(Object.class),
        SUPPLIER_TARGET,
        MethodType.methodType(Object.class));
  }

  @Trace(operationName = "parent")
  private void runUnderTrace(ExecutorService pool) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    // lambda Runnable: this is the task whose propagation strategy (wrap vs field) we compare
    pool.execute(latch::countDown);
    latch.await();
  }

  private static void runTarget() {}

  private static Object supplyTarget() {
    return null;
  }

  @Fork
  public static class NoAgent extends LambdaExecutorBenchmark {}

  @Fork(jvmArgsAppend = AGENT)
  public static class AgentLambdaOff extends LambdaExecutorBenchmark {}

  @Fork(jvmArgsAppend = {AGENT, "-Ddd.trace.lambda.enabled=true"})
  public static class AgentLambdaOn extends LambdaExecutorBenchmark {}
}
