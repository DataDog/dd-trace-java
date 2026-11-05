package datadog.trace.instrumentation.play26

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise$
import scala.runtime.AbstractFunction1
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

class PlayCallbackExclusionTest extends InstrumentationSpecification {
  def "Play finisher skips capture and preserves application callback context: failure=#failure"() {
    setup:
    def outcome = failure ? new Failure(new IllegalStateException("request failed")) : new Success(play.mvc.Results.notFound().asScala())
    def promise = Promise$.MODULE$.apply()
    def executor = new QueuedExecutionContext()
    def application = new ApplicationCallback()
    def request = startSpan("test", "play.request")
    def scope = activateSpan(request)

    when:
    int before = ScopeDiagnostics.report().records().size()
    promise.future().onComplete(new RequestCompleteCallback(scope), executor)
    int registrationCaptures = ScopeDiagnostics.report().records().size() - before
    promise.future().onComplete(application, executor)
    scope.close()
    scope = null
    before = ScopeDiagnostics.report().records().size()
    promise.complete(outcome)
    int completionCaptures = ScopeDiagnostics.report().records().size() - before
    executor.drain()

    then:
    registrationCaptures == 0
    completionCaptures == 0
    application.calls == 1
    application.parent.is(request)
    application.child.parentId == request.spanId
    request.isError() == failure
    if (failure) {
      assert request.getTag("error.type") == IllegalStateException.name
    } else {
      assert request.resourceName.toString() == "404"
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER[0].size() == 2

    cleanup:
    scope?.close()
    promise.tryComplete(outcome)
    executor.drain()
    request.finish()

    where:
    failure << [false, true]
  }

  private static class ApplicationCallback extends AbstractFunction1<Try, Object> {
    int calls
    AgentSpan parent
    AgentSpan child

    @Override
    Object apply(Try result) {
      calls++
      parent = activeSpan()
      child = startSpan("test", "application")
      child.finish()
      null
    }
  }

  private static class QueuedExecutionContext implements ExecutionContext {
    private final Queue<Runnable> tasks = new ArrayDeque<>()

    @Override
    void execute(Runnable task) {
      tasks.add(task)
    }

    @Override
    void reportFailure(Throwable failure) {
      throw new AssertionError(failure)
    }

    @Override
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
