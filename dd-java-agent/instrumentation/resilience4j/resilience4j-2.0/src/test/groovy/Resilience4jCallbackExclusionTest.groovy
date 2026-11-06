import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.instrumentation.resilience4j.Resilience4jSpan
import datadog.trace.instrumentation.resilience4j.Resilience4jSpanDecorator
import datadog.trace.instrumentation.resilience4j.WrapperWithContext.SupplierOfCompletionStageWithContext
import datadog.trace.instrumentation.resilience4j.WrapperWithContext.SupplierOfFutureWithContext

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.BiConsumer
import java.util.function.Supplier

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

class Resilience4jCallbackExclusionTest extends InstrumentationSpecification {
  def "#kind finisher skips capture: completed=#completed failure=#failure nested=#nested"() {
    setup:
    def source = new CompletableFuture<String>()
    def cause = new IllegalArgumentException("delegate failed")
    if (completed) {
      complete(source, failure ? cause : null)
    }
    AgentSpan resilience = null
    AgentSpan delegateChild = null
    Supplier delegate = {
      resilience = activeSpan()
      delegateChild = startSpan("test", "delegate.child")
      delegateChild.finish()
      source
    } as Supplier
    def wrapper = kind == "stage" ? new SupplierOfCompletionStageWithContext(delegate, Resilience4jSpanDecorator.DECORATE, null) :
      new SupplierOfFutureWithContext(delegate, Resilience4jSpanDecorator.DECORATE, null)
    def application = startSpan("test", "application")
    def existing = nested ? Resilience4jSpan.start() : null
    def scope = existing == null ? null : activateSpan(existing)
    def callback = new ApplicationCallback()
    int before = captures()

    when:
    def returned = wrapper.get()
    int finisherCaptures = captures() - before
    assert activeSpan() == existing
    scope?.close()
    scope = activateSpan(application)
    before = captures()
    def downstream = returned.whenComplete(callback)
    int applicationCaptures = captures() - before
    scope.close()
    scope = null
    complete(source, failure ? cause : null)

    then:
    finisherCaptures == 0
    applicationCaptures == (completed ? 0 : 1)
    returned.is(source) == (kind == "future")
    resilience.operationName.toString() == "resilience4j"
    delegateChild.parentId == resilience.spanId
    callback.calls == 1
    callback.parent.is(application)
    callback.child.parentId == application.spanId
    if (failure) {
      try {
        downstream.join()
        assert false: "expected exceptional completion"
      } catch (CompletionException expected) {
        assert expected.cause.is(cause)
      }
    } else {
      assert downstream.join() == "value"
    }
    !resilience.isError()
    if (nested) {
      assert resilience.is(existing)
      assert TEST_WRITER.size() == 0
      existing.finish()
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER[0].size() == 2
    activeSpan() == null

    cleanup:
    scope?.close()
    complete(source, failure ? cause : null)
    existing?.finish()
    application.finish()
    TEST_WRITER.waitForTraces(2)
    assert TEST_WRITER.collectMany { it }.size() == 4

    where:
    [kind, completed, failure, nested] << [["stage", "future"], [false, true], [false, true], [false, true]].combinations()
  }

  private static int captures() {
    ScopeDiagnostics.report().records().size()
  }

  private static void complete(CompletableFuture source, Throwable failure) {
    if (failure == null) {
      source.complete("value")
    } else {
      source.completeExceptionally(failure)
    }
  }

  private static class ApplicationCallback implements BiConsumer<String, Throwable> {
    int calls
    AgentSpan parent
    AgentSpan child

    @Override
    void accept(String value, Throwable failure) {
      calls++
      parent = activeSpan()
      child = startSpan("test", "application.callback")
      child.finish()
    }
  }
}
