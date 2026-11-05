package datadog.trace.instrumentation.httpclient

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.agent.test.scopediag.TrackScopeContinuations

import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.BiConsumer

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK

@TrackScopeContinuations
class ResponseConsumerExclusionTest extends InstrumentationSpecification {
  def "real response consumer captures no continuation: completed=#completed failure=#failure"() {
    setup:
    def response = Stub(HttpResponse) {
      statusCode() >> 200
      headers() >> HttpHeaders.of([:], { name, value -> true })
    }
    def error = failure ? new IOException("response failed") : null
    def source = new CompletableFuture<HttpResponse>()
    if (completed) {
      complete(source, response, error)
    }
    def parent = startSpan("test", "parent")
    def scope = activateSpan(parent)
    def clientSpan = startSpan("test", "client")
    def consumer = new ResponseConsumer(clientSpan)
    def ordinary = new OrdinaryConsumer()
    def control = new CompletableFuture<HttpResponse>()

    when:
    int before = ScopeDiagnostics.report().records().size()
    def result = source.whenComplete(consumer)
    int captures = ScopeDiagnostics.report().records().size() - before
    before = ScopeDiagnostics.report().records().size()
    def controlResult = control.whenComplete(ordinary)
    int controlCaptures = ScopeDiagnostics.report().records().size() - before
    scope.close()
    scope = null
    complete(source, response, error)
    control.complete(response)

    then:
    exclude(COMPLETABLE_FUTURE_CALLBACK, consumer)
    !exclude(COMPLETABLE_FUTURE_CALLBACK, ordinary)
    captures == 0
    controlCaptures == 1
    controlResult.join().is(response)
    if (failure) {
      try {
        result.join()
        assert false: "expected exceptional completion"
      } catch (CompletionException expected) {
        assert expected.cause.is(error)
      }
    } else {
      assert result.join().is(response)
    }
    clientSpan.isError() == failure

    cleanup:
    scope?.close()
    complete(source, response, error)
    control.complete(response)
    parent.finish()
    TEST_WRITER.waitForTraces(1)
    assert TEST_WRITER[0].size() == 2

    where:
    completed | failure
    false     | false
    false     | true
    true      | false
    true      | true
  }

  private static void complete(CompletableFuture source, HttpResponse response, Throwable error) {
    if (error == null) {
      source.complete(response)
    } else {
      source.completeExceptionally(error)
    }
  }

  private static class OrdinaryConsumer implements BiConsumer<HttpResponse, Throwable> {
    @Override
    void accept(HttpResponse response, Throwable error) {}
  }
}
