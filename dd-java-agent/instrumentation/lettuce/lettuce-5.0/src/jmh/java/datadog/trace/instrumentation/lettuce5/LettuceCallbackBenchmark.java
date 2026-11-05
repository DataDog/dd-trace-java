package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Collections.emptyList;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the Lettuce finishing callback with and without its exact capture exclusion.
 *
 * <p>Each operation allocates a Command, AsyncCommand, fresh root span and real
 * LettuceAsyncBiConsumer. It registers under the span, closes the scope, invokes Lettuce's no-arg
 * complete(), and reads the dependent result. The decoded command output is shared read-only.
 * AsyncCommand is constructed at root to isolate callback capture from its own continuation
 * capture. This models successful local callback completion, not Redis I/O, dispatch, cross-thread
 * transfer, error handling, or application throughput.
 *
 * <p>Each span is finished once by the actual consumer. A counting interceptor discards completed
 * traces before serialization or reporting; span creation, finishing and interception remain inside
 * measurement. No long-lived parent retains completed spans.
 *
 * <p>Run with a full agent in every fork, {@code -prof gc -t 1}, and {@code -jvmArgsAppend
 * "-javaagent:/path/to/agent.jar -Dbench.patched=true"}. Compare against an otherwise identical
 * agent without only the Lettuce callback exclusion and {@code bench.patched=false}. The property
 * checks the expected provider registration; it does not enable the production change. Keep JVM,
 * heap, GC and agent configuration identical, and disable external reporting.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class LettuceCallbackBenchmark {
  @Param({"pending", "completed"})
  public String completion;

  private final CountingDiscardInterceptor interceptor = new CountingDiscardInterceptor();
  private StatusOutput<String, String> output;
  private long initialSpans;
  private long operations;

  @Setup(Level.Trial)
  public void setupTrial() {
    String patchedProperty = System.getProperty("bench.patched");
    require(
        "true".equals(patchedProperty) || "false".equals(patchedProperty),
        "Set -Dbench.patched=true or false to identify the agent variant");
    require(AgentTracer.isRegistered(), "A real tracer must be installed with -javaagent");
    require(Context.current() == Context.root(), "Benchmark must begin at root context");
    require("pending".equals(completion) || "completed".equals(completion), "Invalid completion");
    require(
        AgentTracer.get().addTraceInterceptor(interceptor), "Discarding interceptor not installed");
    output = new StatusOutput<>(StringCodec.UTF8);
    output.set(ByteBuffer.wrap(new byte[] {'O', 'K'}));
    // Load the actual command before inspecting its provider's callback exclusion.
    newCommand().complete();
    require(
        exclude(COMPLETABLE_FUTURE_CALLBACK, LettuceAsyncBiConsumer.class.getName())
            == Boolean.parseBoolean(patchedProperty),
        "Lettuce provider registration does not match bench.patched");
    verifyOrdinaryPropagation();
    long before = interceptor.spans.get();
    require("OK".equals(completeCommand(false)), "Pending result changed");
    require("OK".equals(completeCommand(true)), "Completed result changed");
    require(interceptor.spans.get() == before + 2, "Actual callbacks must finish both spans");
    require(Context.current() == Context.root(), "Preflight leaked context");
  }

  private void verifyOrdinaryPropagation() {
    AsyncCommand<String, String, String> source = newCommand();
    AgentSpan span = startSpan("benchmark", "lettuce.preflight");
    require(span != AgentTracer.noopSpan(), "Tracer returned a noop span");
    OrdinaryCallback callback = new OrdinaryCallback();
    CompletableFuture<String> dependent;
    try (ContextScope ignored = activateSpan(span)) {
      AgentTracer.setAsyncPropagationEnabled(true);
      dependent = source.whenComplete(callback);
    }
    try {
      source.complete();
      require("OK".equals(dependent.join()), "Ordinary result changed");
      require(callback.calls == 1 && callback.span == span, "Ordinary propagation is inactive");
    } finally {
      span.finish();
    }
  }

  @Setup(Level.Iteration)
  public void setupIteration() {
    initialSpans = interceptor.spans.get();
    operations = 0;
  }

  @Benchmark
  public String finishCommand() {
    String result = completeCommand("completed".equals(completion));
    operations++;
    return result;
  }

  private String completeCommand(boolean completed) {
    AsyncCommand<String, String, String> source = newCommand();
    if (completed) {
      source.complete();
    }
    AgentSpan span = startSpan("benchmark", "lettuce.callback");
    CompletableFuture<String> dependent;
    try (ContextScope ignored = activateSpan(span)) {
      AgentTracer.setAsyncPropagationEnabled(true);
      dependent = source.whenComplete(new LettuceAsyncBiConsumer<>(span));
    }
    if (!completed) {
      source.complete();
    }
    return dependent.join();
  }

  private AsyncCommand<String, String, String> newCommand() {
    return new AsyncCommand<>(new Command<>(CommandType.GET, output));
  }

  @TearDown(Level.Iteration)
  public void teardownIteration() {
    require(
        interceptor.spans.get() - initialSpans == operations,
        "Expected exactly one completed span per operation");
    require(Context.current() == Context.root(), "Completion leaked context");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static final class OrdinaryCallback implements BiConsumer<String, Throwable> {
    private AgentSpan span;
    private int calls;

    @Override
    public void accept(String value, Throwable failure) {
      span = activeSpan();
      calls++;
    }
  }

  private static final class CountingDiscardInterceptor implements TraceInterceptor {
    private final AtomicLong spans = new AtomicLong();

    @Override
    public Collection<? extends MutableSpan> onTraceComplete(
        Collection<? extends MutableSpan> trace) {
      spans.addAndGet(trace.size());
      return emptyList();
    }

    @Override
    public int priority() {
      return Integer.MIN_VALUE;
    }
  }
}
