package datadog.trace.instrumentation.scala213.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.emptyList;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.java.concurrent.SkipScalaPromiseContextCapture;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import scala.concurrent.ExecutionContext;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;
import scala.runtime.AbstractFunction1;
import scala.util.Success;
import scala.util.Try;

/**
 * Measures Scala promise callback capture exclusion with a real agent.
 *
 * <p>Each operation allocates a promise, fresh span and callback, registers under the span, closes
 * its scope, and runs the queued callback at root. The callback finishes its retained span. A
 * counting interceptor discards completed traces before export. This measures local propagation
 * machinery, not Play request latency, network I/O or cross-thread scheduling.
 *
 * <p>Run the same patched agent in separate forks with {@code -Dbench.exclude=false} and {@code
 * -Dbench.exclude=true}, {@code -prof gc -t 1}. The flag selects an ordinary callback or one
 * implementing {@code SkipScalaPromiseContextCapture}. Also run each with {@code
 * -Ddd.trace.integration.scala_promise_completion_priority.enabled=false} and {@code true}. This
 * comparison does not measure the marker-check overhead on ordinary application callbacks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class ScalaPromiseCallbackBenchmark {
  @Param({"pending", "completed"})
  public String completion;

  private final QueuedExecutionContext executor = new QueuedExecutionContext();
  private final CountingDiscardInterceptor interceptor = new CountingDiscardInterceptor();
  private final Try<Object> result = new Success<>(new Object());
  private boolean exclude;
  private long initialSpans;
  private long operations;

  @Setup(Level.Trial)
  public void setupTrial() {
    String mode = System.getProperty("bench.exclude");
    require("true".equals(mode) || "false".equals(mode), "Set bench.exclude=true or false");
    exclude = Boolean.parseBoolean(mode);
    String priority =
        System.getProperty("dd.trace.integration.scala_promise_completion_priority.enabled");
    require(
        "true".equals(priority) || "false".equals(priority), "Set completion-priority explicitly");
    require(
        InstrumenterConfig.get().isScalaPromiseCompletionPriorityEnabled()
            == Boolean.parseBoolean(priority),
        "Completion-priority configuration mismatch");
    require(AgentTracer.isRegistered(), "Run with a real -javaagent");
    require(Context.current() == Context.root(), "Benchmark must start at root");
    require(AgentTracer.get().addTraceInterceptor(interceptor), "Discard interceptor rejected");
    AgentSpan span = startSpan("benchmark", "scala.preflight");
    OrdinaryCallback ordinary = new OrdinaryCallback();
    Promise<Object> promise = Promise$.MODULE$.apply();
    try (ContextScope ignored = activateSpan(span)) {
      promise.future().onComplete(ordinary, executor);
    }
    promise.complete(result);
    executor.drain();
    require(ordinary.span == span, "Ordinary promise propagation inactive");
    span.finish();
    long before = interceptor.spans.get();
    FinishingCallback callback = completeRequest(false);
    require(callback.calls == 1, "Callback did not execute once");
    require(
        exclude ? callback.active == null : callback.active == callback.span,
        "Callback propagation does not match exclusion mode");
    require(interceptor.spans.get() == before + 1, "Callback did not finish its span");
    require(Context.current() == Context.root(), "Preflight leaked context");
  }

  @Setup(Level.Iteration)
  public void setupIteration() {
    initialSpans = interceptor.spans.get();
    operations = 0;
  }

  @Benchmark
  public Object completeCallback() {
    FinishingCallback callback = completeRequest("completed".equals(completion));
    operations++;
    return callback.value;
  }

  private FinishingCallback completeRequest(boolean completed) {
    Promise<Object> promise = Promise$.MODULE$.apply();
    if (completed) {
      promise.complete(result);
    }
    AgentSpan span = startSpan("benchmark", "scala.callback");
    FinishingCallback callback =
        exclude ? new SkippingFinishingCallback(span) : new FinishingCallback(span);
    try (ContextScope ignored = activateSpan(span)) {
      promise.future().onComplete(callback, executor);
    }
    if (!completed) {
      promise.complete(result);
    }
    executor.drain();
    return callback;
  }

  @TearDown(Level.Iteration)
  public void teardownIteration() {
    require(interceptor.spans.get() - initialSpans == operations, "Wrong finished span count");
    require(Context.current() == Context.root(), "Callback leaked context");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static class FinishingCallback extends AbstractFunction1<Try<Object>, Object> {
    private final AgentSpan span;
    private AgentSpan active;
    private Object value;
    private int calls;

    private FinishingCallback(AgentSpan span) {
      this.span = span;
    }

    @Override
    public Object apply(Try<Object> result) {
      active = activeSpan();
      calls++;
      value = result.get();
      span.finish();
      return null;
    }
  }

  private static final class SkippingFinishingCallback extends FinishingCallback
      implements SkipScalaPromiseContextCapture {
    private SkippingFinishingCallback(AgentSpan span) {
      super(span);
    }
  }

  private static final class OrdinaryCallback extends AbstractFunction1<Try<Object>, Object> {
    private AgentSpan span;

    @Override
    public Object apply(Try<Object> result) {
      span = activeSpan();
      return null;
    }
  }

  private static final class QueuedExecutionContext implements ExecutionContext {
    private Runnable task;

    @Override
    public void execute(Runnable runnable) {
      require(task == null, "Expected one queued callback");
      task = runnable;
    }

    void drain() {
      Runnable next = task;
      task = null;
      require(next != null, "Callback was not scheduled");
      next.run();
    }

    @Override
    public void reportFailure(Throwable failure) {
      throw new AssertionError(failure);
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
