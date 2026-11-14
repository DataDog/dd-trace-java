package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
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

/**
 * Measures whenComplete registration and completion for ordinary and opted-in agent callbacks.
 *
 * <p>Each operation creates a future, registers a preallocated callback under an active span,
 * completes under root context on the same thread, and reads the dependent result. Completed
 * futures are an inline control. Span creation and callback allocation are outside measurement;
 * executor scheduling, thread transfer and network work are not modeled.
 *
 * <p>Run with an actual agent in every JMH fork and the GC profiler for bytes/op. For example,
 * build with {@code :dd-java-agent:agent-bootstrap:jmhJar}, then select this benchmark with {@code
 * -prof gc -t 1 -f 3 -wi 3 -w 1s -i 5 -r 1s -tu ns -bm avgt} and {@code -jvmArgsAppend
 * "-javaagent:/path/to/agent.jar -Dbench.patched=true"}. Use {@code bench.patched=false} for an
 * original-hook comparison agent retaining the inert exclusion enum. This property checks expected
 * behavior; it does not enable the production fix. Keep JVM, heap, GC and agent configuration
 * identical across variants and disable external reporting. Compare ordinary-path overhead as well
 * as agent-callback savings.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Thread)
public class CompletableFutureCallbackBenchmark {
  @Param({"ordinary", "agentOwned"})
  public String callbackKind;

  @Param({"pending", "completed"})
  public String completion;

  private final Integer payload = Integer.valueOf(12345);
  private Callback callback;
  private AgentSpan span;
  private ContextScope registrationScope;
  private long operations;

  @Setup(Level.Trial)
  public void setupTrial() {
    String patchedProperty = System.getProperty("bench.patched");
    require(
        "true".equals(patchedProperty) || "false".equals(patchedProperty),
        "Set -Dbench.patched=true or false to identify the agent variant");
    require(AgentTracer.isRegistered(), "A real Datadog tracer must be installed with -javaagent");
    require(
        "ordinary".equals(callbackKind) || "agentOwned".equals(callbackKind),
        "Invalid callbackKind");
    require("pending".equals(completion) || "completed".equals(completion), "Invalid completion");
    ExcludeFilter.add(
        singletonMap(
            ExcludeFilter.ExcludeType.valueOf("COMPLETABLE_FUTURE_CALLBACK"),
            singleton(Excluded.class.getName())));
    // Register before either callback class can populate the filter's ClassValue.
    callback = "agentOwned".equals(callbackKind) ? new Excluded() : new Ordinary();
    boolean patched = Boolean.parseBoolean(patchedProperty);
    AgentSpan checkSpan = AgentTracer.startSpan("benchmark", "callback.preflight");
    require(checkSpan != AgentTracer.noopSpan(), "Tracer returned a noop span");
    try (ContextScope ignored = AgentTracer.activateSpan(checkSpan)) {
      AgentTracer.setAsyncPropagationEnabled(true);
      verify(new Ordinary(), checkSpan, false, false);
      verify(new Excluded(), checkSpan, patched, false);
      verify(new Ordinary(), checkSpan, false, true);
      verify(new Excluded(), checkSpan, false, true);
    } finally {
      checkSpan.finish();
    }
  }

  private void verify(Callback callback, AgentSpan expected, boolean excluded, boolean completed) {
    callback.observeContext = true;
    CompletableFuture<Integer> source = new CompletableFuture<>();
    if (completed) {
      source.complete(payload);
    }
    CompletableFuture<Integer> dependent = source.whenComplete(callback);
    if (!completed) {
      try (ContextScope ignored = Context.root().attach()) {
        if (excluded) {
          expected = AgentTracer.activeSpan();
        }
        source.complete(payload);
      }
    }
    require(dependent.join() == payload, "Dependent result changed");
    require(
        callback.calls == 1 && callback.total == payload.intValue(), "Callback not invoked once");
    require(
        callback.observedSpan == expected,
        "Unexpected callback context for "
            + callback.getClass().getName()
            + "; instrumentation or agent variant does not match bench.patched");
    callback.observeContext = false;
  }

  @Setup(Level.Iteration)
  public void setupIteration() {
    span = AgentTracer.startSpan("benchmark", "callback.iteration");
    registrationScope = AgentTracer.activateSpan(span);
    AgentTracer.setAsyncPropagationEnabled(true);
    operations = 0;
    callback.calls = 0;
    callback.total = 0;
  }

  @Benchmark
  public long whenComplete() {
    CompletableFuture<Integer> source = new CompletableFuture<>();
    boolean completed = "completed".equals(completion);
    if (completed) {
      source.complete(payload);
    }
    CompletableFuture<Integer> dependent = source.whenComplete(callback);
    if (!completed) {
      // No thread queue or scheduling cost: only a context handoff on this thread.
      try (ContextScope ignored = Context.root().attach()) {
        source.complete(payload);
      }
    }
    operations++;
    return callback.total + dependent.join().intValue();
  }

  @TearDown(Level.Iteration)
  public void teardownIteration() {
    try {
      require(
          callback.calls == operations && callback.total == operations * payload.intValue(),
          "Callback count or result mismatch");
      require(AgentTracer.activeSpan() == span, "Registration context was not restored");
    } finally {
      registrationScope.close();
      span.finish();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  public abstract static class Callback implements BiConsumer<Integer, Throwable> {
    long calls;
    long total;
    boolean observeContext;
    AgentSpan observedSpan;

    final void record(Integer value, Throwable failure) {
      if (failure != null) {
        throw new AssertionError(failure);
      }
      calls++;
      total += value.intValue();
      if (observeContext) {
        observedSpan = AgentTracer.activeSpan();
      }
    }
  }

  public static final class Ordinary extends Callback {
    @Override
    public void accept(Integer value, Throwable failure) {
      record(value, failure);
    }
  }

  public static final class Excluded extends Callback {
    @Override
    public void accept(Integer value, Throwable failure) {
      record(value, failure);
    }
  }
}
