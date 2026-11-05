import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.function.BiConsumer

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions.finishSpan
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions.wrapAsyncResult

class AsyncResultCallbackExclusionTest extends InstrumentationSpecification {
  def "#factory finisher skips capture: completed=#completed outcome=#outcome"() {
    setup:
    def source = new CompletableFuture<String>()
    def cause = new IllegalArgumentException("source failed")
    Throwable failure = outcome == "plain" ? cause : outcome == "completion" ? new CompletionException(cause) :
      outcome == "execution" ? new ExecutionException(cause) : outcome == "nested" ? new CompletionException(new CompletionException(cause)) : null
    if (completed) {
      complete(source, outcome, failure)
    }
    def stage = Stub(CompletionStage) {
      whenComplete(_) >> { BiConsumer callback -> source.whenComplete(callback) }
    }
    def parent = startSpan("test", "parent")
    def scope = activateSpan(parent)
    def span = startSpan("test", "async")
    def application = new ApplicationCallback()
    int before = captures()

    when:
    def dependent = factory == "direct" ? source.whenComplete(finishSpan(span)) :
      factory == "stage" ? wrapAsyncResult(stage, CompletionStage, span) : wrapAsyncResult(source, CompletableFuture, span)
    int finisherCaptures = captures() - before
    before = captures()
    def downstream = dependent.whenComplete(application)
    int applicationCaptures = captures() - before
    scope.close()
    scope = null
    complete(source, outcome, failure)

    then:
    finisherCaptures == 0
    applicationCaptures == (completed ? 0 : 1)
    application.calls == 1
    application.parent.is(parent)
    !dependent.is(source)
    if (outcome == "success") {
      assert downstream.join() == "value"
    } else {
      try {
        downstream.join()
        assert false: "expected exceptional completion"
      } catch (CompletionException expected) {
        assert outcome == "cancel" ? expected.cause instanceof java.util.concurrent.CancellationException :
        expected.is(failure) || expected.cause.is(failure)
      }
    }
    span.isError() == (outcome != "success")
    if (outcome != "success") {
      assert span.getTag("error.type") == (outcome == "cancel" ? java.util.concurrent.CancellationException.name :
      outcome == "nested" ? CompletionException.name : IllegalArgumentException.name)
    }
    activeSpan() == null

    cleanup:
    scope?.close()
    complete(source, outcome, failure)
    parent.finish()
    TEST_WRITER.waitForTraces(1)
    assert TEST_WRITER[0].size() == 2

    where:
    [factory, completed, outcome] << [
      ["direct", "future", "stage"],
      [false, true],
      ["success", "plain", "completion", "execution", "nested", "cancel"]
    ].combinations().findAll {
      it[0] != "future" || !it[1]
    }
  }

  def "already completed future remains unwrapped: #outcome"() {
    setup:
    def source = new CompletableFuture<String>()
    complete(source, outcome, new IllegalArgumentException("source failed"))
    def span = startSpan("test", "async")
    def scope = activateSpan(span)
    int before = captures()

    expect:
    wrapAsyncResult(source, CompletableFuture, span) == null
    captures() == before
    TEST_WRITER.size() == 0

    cleanup:
    scope.close()
    span.finish()
    TEST_WRITER.waitForTraces(1)

    where:
    outcome << ["success", "plain", "cancel"]
  }

  private static int captures() {
    ScopeDiagnostics.report().records().size()
  }

  private static void complete(CompletableFuture source, String outcome, Throwable failure) {
    if (outcome == "cancel") {
      source.cancel(false)
    } else if (failure != null) {
      source.completeExceptionally(failure)
    } else {
      source.complete("value")
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    int calls
    AgentSpan parent

    @Override
    void accept(String value, Throwable failure) {
      calls++
      parent = activeSpan()
    }
  }
}
