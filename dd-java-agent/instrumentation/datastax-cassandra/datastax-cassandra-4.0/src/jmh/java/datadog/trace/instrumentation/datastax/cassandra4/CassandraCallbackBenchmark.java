package datadog.trace.instrumentation.datastax.cassandra4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Collections.emptyList;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Proxy;
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
 * Measures Cassandra's real span-finishing callback with and without its exact capture exclusion.
 *
 * <p>Each operation creates a CompletableFuture, fresh root span and AsyncResultSetConsumer,
 * registers under the span, closes the scope, completes at root, and reads the dependent result.
 * The shared result fixture supplies execution metadata with no coordinator or columns. This
 * isolates local successful callback completion, not query execution, response decoding, Cassandra
 * I/O, cross-thread transfer, or application throughput.
 *
 * <p>A counting interceptor discards completed traces before serialization/export and verifies one
 * finished span per operation. Span creation, callback allocation, decoration, finishing and
 * interception remain inside measurement. No long-lived parent retains completed spans.
 *
 * <p>Build with this module's compileJmhJava or jmhJar task. Run with a full agent in each fork,
 * {@code -prof gc -t 1}, and {@code -jvmArgsAppend "-javaagent:/path/to/agent.jar
 * -Dbench.patched=true"}. Compare with an otherwise identical agent retaining the named callback
 * but without its provider exclusion and {@code bench.patched=false}. This property checks the
 * variant; it does not enable the production change. Keep JVM, heap, GC and agent configuration
 * identical and disable external reporting.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@Threads(1)
@State(Scope.Thread)
public class CassandraCallbackBenchmark {
  @Param({"pending", "completed"})
  public String completion;

  private final CountingDiscardInterceptor interceptor = new CountingDiscardInterceptor();
  private AsyncResultSet response;
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
    ExecutionInfo executionInfo =
        (ExecutionInfo)
            Proxy.newProxyInstance(
                ExecutionInfo.class.getClassLoader(),
                new Class<?>[] {ExecutionInfo.class},
                (proxy, method, args) -> {
                  if ("getCoordinator".equals(method.getName())) {
                    return null;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    response =
        (AsyncResultSet)
            Proxy.newProxyInstance(
                AsyncResultSet.class.getClassLoader(),
                new Class<?>[] {AsyncResultSet.class},
                (proxy, method, args) -> {
                  if ("getExecutionInfo".equals(method.getName())) {
                    return executionInfo;
                  }
                  if ("getColumnDefinitions".equals(method.getName())) {
                    return null;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    require(
        exclude(COMPLETABLE_FUTURE_CALLBACK, AsyncResultSetConsumer.class.getName())
            == Boolean.parseBoolean(patchedProperty),
        "Cassandra provider registration does not match bench.patched");
    verifyOrdinaryPropagation();
    long before = interceptor.spans.get();
    require(completeRequest(false) == response, "Pending result changed");
    require(completeRequest(true) == response, "Completed result changed");
    require(interceptor.spans.get() == before + 2, "Actual callbacks must finish both spans");
    require(Context.current() == Context.root(), "Preflight leaked context");
  }

  private void verifyOrdinaryPropagation() {
    CompletableFuture<AsyncResultSet> source = new CompletableFuture<>();
    AgentSpan span = startSpan("benchmark", "cassandra.preflight");
    require(span != AgentTracer.noopSpan(), "Tracer returned a noop span");
    OrdinaryCallback callback = new OrdinaryCallback();
    CompletableFuture<AsyncResultSet> dependent;
    try (ContextScope ignored = activateSpan(span)) {
      AgentTracer.setAsyncPropagationEnabled(true);
      dependent = source.whenComplete(callback);
    }
    try {
      source.complete(response);
      require(dependent.join() == response, "Ordinary result changed");
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
  public AsyncResultSet finishRequest() {
    AsyncResultSet result = completeRequest("completed".equals(completion));
    operations++;
    return result;
  }

  private AsyncResultSet completeRequest(boolean completed) {
    CompletableFuture<AsyncResultSet> source = new CompletableFuture<>();
    if (completed) {
      source.complete(response);
    }
    AgentSpan span = startSpan("benchmark", "cassandra.callback");
    CompletableFuture<AsyncResultSet> dependent;
    try (ContextScope ignored = activateSpan(span)) {
      AgentTracer.setAsyncPropagationEnabled(true);
      dependent = source.whenComplete(new AsyncResultSetConsumer(span));
    }
    if (!completed) {
      source.complete(response);
    }
    return dependent.join();
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

  private static final class OrdinaryCallback implements BiConsumer<AsyncResultSet, Throwable> {
    private AgentSpan span;
    private int calls;

    @Override
    public void accept(AsyncResultSet value, Throwable failure) {
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
