import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.SCALA_PROMISE_CALLBACK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.add;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayDeque;
import org.junit.jupiter.api.TestInstance;
import org.tabletest.junit.TableTest;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;
import scala.runtime.AbstractFunction1;
import scala.util.Try;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ScalaPromiseCallbackExclusionTestBase extends AbstractInstrumentationTest {
  static {
    add(singletonMap(SCALA_PROMISE_CALLBACK, singletonList(ExcludedCallback.class.getName())));
  }

  boolean completionPriority() {
    return false;
  }

  @TableTest({
    "scenario             | kind                           | registerActive | completeActive | failure      ",
    "registration context | {EXCLUDED, ORDINARY, UNLISTED} | true           | false          | {false, true}",
    "both contexts        | {EXCLUDED, ORDINARY, UNLISTED} | true           | true           | {false, true}",
    "completion context   | {EXCLUDED, ORDINARY, UNLISTED} | false          | true           | {false, true}"
  })
  void pendingCallback(
      CallbackKind kind, boolean registerActive, boolean completeActive, boolean failure)
      throws Exception {
    Promise<String> promise = Promise$.MODULE$.apply();
    QueuedContext executor = new QueuedContext();
    OrdinaryCallback callback = callback(kind);
    AgentSpan registration = startSpan("test", "registration");
    AgentSpan completion = startSpan("test", "completion");
    ContextScope scope = registerActive ? activateSpan(registration) : null;
    int before = captures();

    try {
      promise.future().onComplete(callback, executor);
      int registrationCaptures = captures() - before;
      if (scope != null) {
        scope.close();
      }
      scope = completeActive ? activateSpan(completion) : null;
      before = captures();
      if (failure) {
        promise.failure(new IllegalStateException("source failure"));
      } else {
        promise.success("value");
      }
      int completionCaptures = captures() - before;
      if (scope != null) {
        scope.close();
      }
      scope = null;
      executor.drain();

      assertEquals(kind != CallbackKind.EXCLUDED && registerActive ? 1 : 0, registrationCaptures);
      assertEquals(
          kind != CallbackKind.EXCLUDED
                  && completeActive
                  && (!registerActive || completionPriority())
              ? 1
              : 0,
          completionCaptures);
      assertEquals(1, callback.calls);
      assertEquals(failure, callback.failed);
      assertSame(
          kind == CallbackKind.EXCLUDED
              ? null
              : completeActive && (!registerActive || completionPriority())
                  ? completion
                  : registration,
          callback.parent);
      assertEquals(
          callback.parent == null ? 0 : callback.parent.getSpanId(), callback.child.getParentId());
      assertNull(activeSpan());
    } finally {
      if (scope != null) {
        scope.close();
      }
      promise.trySuccess("cleanup");
      executor.drain();
      registration.finish();
      completion.finish();
      writer.waitForTraces(kind == CallbackKind.EXCLUDED ? 3 : 2);
      assertEquals(3, writer.stream().mapToInt(trace -> trace.size()).sum());
    }
  }

  @TableTest({
    "scenario           | kind                 | failure      ",
    "completed callback | {EXCLUDED, ORDINARY} | {false, true}"
  })
  void alreadyCompletedCallback(CallbackKind kind, boolean failure) throws Exception {
    Promise<String> promise = Promise$.MODULE$.apply();
    QueuedContext executor = new QueuedContext();
    OrdinaryCallback callback = callback(kind);
    AgentSpan registration = startSpan("test", "registration");
    AgentSpan completion = startSpan("test", "completion");
    ContextScope scope = activateSpan(completion);

    try {
      if (failure) {
        promise.failure(new IllegalStateException("source failure"));
      } else {
        promise.success("value");
      }
      scope.close();
      scope = activateSpan(registration);
      int before = captures();
      promise.future().onComplete(callback, executor);
      int registrationCaptures = captures() - before;
      scope.close();
      scope = null;
      executor.drain();

      assertEquals(
          kind == CallbackKind.EXCLUDED ? 0 : completionPriority() ? 2 : 1, registrationCaptures);
      assertEquals(1, callback.calls);
      assertEquals(failure, callback.failed);
      assertSame(
          kind == CallbackKind.EXCLUDED ? null : completionPriority() ? completion : registration,
          callback.parent);
      assertEquals(
          callback.parent == null ? 0 : callback.parent.getSpanId(), callback.child.getParentId());
      assertNull(activeSpan());
    } finally {
      if (scope != null) {
        scope.close();
      }
      executor.drain();
      registration.finish();
      completion.finish();
      writer.waitForTraces(kind == CallbackKind.EXCLUDED ? 3 : 2);
      assertEquals(3, writer.stream().mapToInt(trace -> trace.size()).sum());
    }
  }

  private static int captures() {
    return ScopeDiagnostics.report().records().size();
  }

  private static OrdinaryCallback callback(CallbackKind kind) {
    return kind == CallbackKind.EXCLUDED
        ? new ExcludedCallback()
        : kind == CallbackKind.UNLISTED ? new UnlistedSubclass() : new OrdinaryCallback();
  }

  enum CallbackKind {
    EXCLUDED,
    ORDINARY,
    UNLISTED
  }

  static class OrdinaryCallback extends AbstractFunction1<Try<String>, Object> {
    int calls;
    boolean failed;
    AgentSpan parent;
    DDSpan child;

    @Override
    public Object apply(Try<String> result) {
      calls++;
      failed = result.isFailure();
      parent = activeSpan();
      child = (DDSpan) startSpan("test", "application");
      child.finish();
      return null;
    }
  }

  static class ExcludedCallback extends OrdinaryCallback {}

  static class UnlistedSubclass extends ExcludedCallback {}

  static class QueuedContext implements ExecutionContext {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    @Override
    public void reportFailure(Throwable failure) {
      throw new AssertionError(failure);
    }

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

@WithConfig(key = "trace.integration.scala_promise_completion_priority.enabled", value = "true")
abstract class ScalaPromiseCallbackExclusionCompletionPriorityTestBase
    extends ScalaPromiseCallbackExclusionTestBase {
  @Override
  boolean completionPriority() {
    return true;
  }
}
