import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise$
import scala.runtime.AbstractFunction1
import scala.util.Try

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.add
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.SCALA_PROMISE_CALLBACK

abstract class ScalaPromiseCallbackExclusionTestBase extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    add([(SCALA_PROMISE_CALLBACK): [ExcludedCallback.name]])
  }

  boolean completionPriority() {
    false
  }

  def "pending #kind callback: registration=#registerActive completion=#completeActive failure=#failure"() {
    setup:
    def promise = Promise$.MODULE$.apply()
    def executor = new QueuedContext()
    def callback = kind.newInstance()
    def registration = startSpan("test", "registration")
    def completion = startSpan("test", "completion")
    def scope = registerActive ? activateSpan(registration) : null
    int before = captures()

    when:
    promise.future().onComplete(callback, executor)
    int registrationCaptures = captures() - before
    scope?.close()
    scope = completeActive ? activateSpan(completion) : null
    before = captures()
    if (failure) {
      promise.failure(new IllegalStateException("source failure"))
    } else {
      promise.success("value")
    }
    int completionCaptures = captures() - before
    scope?.close()
    scope = null
    executor.drain()

    then:
    registrationCaptures == (kind != ExcludedCallback && registerActive ? 1 : 0)
    completionCaptures == (kind != ExcludedCallback && completeActive && (!registerActive || completionPriority()) ? 1 : 0)
    callback.calls == 1
    callback.failed == failure
    callback.parent == (kind == ExcludedCallback ? null : completeActive && (!registerActive || completionPriority()) ? completion : registration)
    callback.child.parentId == (callback.parent == null ? 0 : callback.parent.spanId)
    activeSpan() == null

    cleanup:
    scope?.close()
    promise.trySuccess("cleanup")
    executor.drain()
    registration.finish()
    completion.finish()
    TEST_WRITER.waitForTraces(kind == ExcludedCallback ? 3 : 2)
    assert TEST_WRITER.collectMany { it }.size() == 3

    where:
    [kind, registerActive, completeActive, failure] << [
      [ExcludedCallback, OrdinaryCallback, UnlistedSubclass],
      [[true, false], [true, true], [false, true]],
      [false, true]
    ].combinations().collect { entry -> [entry[0], entry[1][0], entry[1][1], entry[2]] }
  }

  def "already completed #kind callback preserves priority on failure=#failure"() {
    setup:
    def promise = Promise$.MODULE$.apply()
    def executor = new QueuedContext()
    def callback = kind.newInstance()
    def registration = startSpan("test", "registration")
    def completion = startSpan("test", "completion")
    def scope = activateSpan(completion)
    if (failure) {
      promise.failure(new IllegalStateException("source failure"))
    } else {
      promise.success("value")
    }
    scope.close()
    scope = activateSpan(registration)
    int before = captures()

    when:
    promise.future().onComplete(callback, executor)
    int registrationCaptures = captures() - before
    scope.close()
    scope = null
    executor.drain()

    then:
    registrationCaptures == (kind == ExcludedCallback ? 0 : completionPriority() ? 2 : 1)
    callback.calls == 1
    callback.failed == failure
    callback.parent == (kind == ExcludedCallback ? null : completionPriority() ? completion : registration)
    callback.child.parentId == (callback.parent == null ? 0 : callback.parent.spanId)
    activeSpan() == null

    cleanup:
    scope?.close()
    executor.drain()
    registration.finish()
    completion.finish()
    TEST_WRITER.waitForTraces(kind == ExcludedCallback ? 3 : 2)
    assert TEST_WRITER.collectMany { it }.size() == 3

    where:
    [kind, failure] << [[ExcludedCallback, OrdinaryCallback], [false, true]].combinations()
  }

  private static int captures() {
    ScopeDiagnostics.report().records().size()
  }

  static class OrdinaryCallback extends AbstractFunction1<Try<String>, Object> {
    int calls
    boolean failed
    AgentSpan parent
    AgentSpan child

    @Override
    Object apply(Try<String> result) {
      calls++
      failed = result.isFailure()
      parent = activeSpan()
      child = startSpan("test", "application")
      child.finish()
      null
    }
  }

  static class ExcludedCallback extends OrdinaryCallback {}

  static class UnlistedSubclass extends ExcludedCallback {}

  static class QueuedContext implements ExecutionContext {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>()

    @Override
    void execute(Runnable task) {
      tasks.add(task)
    }

    @Override
    void reportFailure(Throwable failure) {
      throw new AssertionError(failure)
    }

    ExecutionContext prepare() {
      this
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.remove().run()
      }
    }
  }
}

abstract class ScalaPromiseCallbackExclusionCompletionPriorityTestBase extends ScalaPromiseCallbackExclusionTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("dd.trace.integration.scala_promise_completion_priority.enabled", "true")
  }

  @Override
  boolean completionPriority() {
    true
  }
}
