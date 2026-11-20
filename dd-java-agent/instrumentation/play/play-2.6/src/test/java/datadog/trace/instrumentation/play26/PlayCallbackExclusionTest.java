package datadog.trace.instrumentation.play26;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.Subscription;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.api.mvc.Result;
import play.mvc.Results;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;
import scala.runtime.AbstractFunction1;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

class PlayCallbackExclusionTest extends AbstractInstrumentationTest {
  @ParameterizedTest(name = "Request-end hook restores completion context: workerActive={0}")
  @ValueSource(booleans = {false, true})
  void requestEndHooksUseRequestContextAndRestoreCompletionContext(boolean workerActive)
      throws Exception {
    Object requestData = new Object();
    AtomicInteger hookCalls = new AtomicInteger();
    AtomicReference<AgentSpan> hookSpan = new AtomicReference<>();
    AtomicReference<RequestContext> hookContext = new AtomicReference<>();
    Events<Object> events = Events.get();
    Subscription subscription =
        tracer
            .getSubscriptionService(RequestContextSlot.IAST)
            .registerCallback(
                events.requestEnded(),
                new BiFunction<RequestContext, IGSpanInfo, Flow<Void>>() {
                  @Override
                  public Flow<Void> apply(RequestContext context, IGSpanInfo spanInfo) {
                    hookCalls.incrementAndGet();
                    hookSpan.set(activeSpan());
                    hookContext.set(context);
                    return Flow.ResultFlow.empty();
                  }
                });
    AgentSpan request =
        tracer
            .buildSpan("test", "play.request")
            .ignoreActiveSpan()
            .withRequestContextData(RequestContextSlot.IAST, requestData)
            .start();
    Promise<Result> promise = Promise$.MODULE$.apply();
    QueuedExecutionContext executor = new QueuedExecutionContext();
    Try<Result> outcome = new Success<>(Results.ok().asScala());
    AgentSpan worker = workerActive ? startSpan("test", "worker") : null;
    try {
      int before = ScopeDiagnostics.report().records().size();
      try (ContextScope scope = activateSpan(request)) {
        promise.future().onComplete(new RequestCompleteCallback(scope), executor);
      }
      assertEquals(0, ScopeDiagnostics.report().records().size() - before);
      try (ContextScope ignored = workerActive ? activateSpan(worker) : null) {
        before = ScopeDiagnostics.report().records().size();
        promise.complete(outcome);
        executor.drain();
        assertEquals(0, ScopeDiagnostics.report().records().size() - before);
        assertSame(worker, activeSpan());
      }
      assertEquals(1, hookCalls.get());
      assertSame(request, hookSpan.get());
      assertSame(requestData, hookContext.get().getData(RequestContextSlot.IAST));
      assertNull(activeSpan());
    } finally {
      subscription.cancel();
      promise.tryComplete(outcome);
      executor.drain();
      request.finish();
      if (worker != null) {
        worker.finish();
      }
    }
  }

  @ParameterizedTest(name = "Play finisher preserves application context: failure={0}")
  @ValueSource(booleans = {false, true})
  void finisherSkipsCaptureAndPreservesApplicationContext(boolean failure) throws Exception {
    Try<Result> outcome =
        failure
            ? new Failure<>(new IllegalStateException("request failed"))
            : new Success<>(Results.notFound().asScala());
    Promise<Result> promise = Promise$.MODULE$.apply();
    QueuedExecutionContext executor = new QueuedExecutionContext();
    ApplicationCallback application = new ApplicationCallback();
    AgentSpan request = startSpan("test", "play.request");
    ContextScope scope = activateSpan(request);

    try {
      int before = ScopeDiagnostics.report().records().size();
      promise.future().onComplete(new RequestCompleteCallback(scope), executor);
      int registrationCaptures = ScopeDiagnostics.report().records().size() - before;
      promise.future().onComplete(application, executor);
      scope.close();
      scope = null;
      before = ScopeDiagnostics.report().records().size();
      promise.complete(outcome);
      int completionCaptures = ScopeDiagnostics.report().records().size() - before;
      executor.drain();

      assertEquals(0, registrationCaptures);
      assertEquals(0, completionCaptures);
      assertEquals(1, application.calls);
      assertSame(request, application.parent);
      assertEquals(request.getSpanId(), application.child.getParentId());
      assertEquals(failure, request.isError());
      if (failure) {
        assertEquals(IllegalStateException.class.getName(), request.getTag("error.type"));
      } else {
        assertEquals("404", request.getResourceName().toString());
      }
      writer.waitForTraces(1);
      assertEquals(2, writer.get(0).size());
    } finally {
      if (scope != null) {
        scope.close();
      }
      promise.tryComplete(outcome);
      executor.drain();
      request.finish();
    }
  }

  private static class ApplicationCallback extends AbstractFunction1<Try<Result>, Object> {
    int calls;
    AgentSpan parent;
    DDSpan child;

    @Override
    public Object apply(Try<Result> result) {
      calls++;
      parent = activeSpan();
      child = (DDSpan) startSpan("test", "application");
      child.finish();
      return null;
    }
  }

  private static class QueuedExecutionContext implements ExecutionContext {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    @Override
    public void reportFailure(Throwable failure) {
      throw new AssertionError(failure);
    }

    @Override
    public ExecutionContext prepare() {
      return this;
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.remove().run();
      }
    }
  }
}
