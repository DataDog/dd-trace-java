import com.openai.core.http.Headers
import com.openai.core.http.HttpResponseFor
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.api.config.LlmObsConfig
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.instrumentation.openai_java.HttpResponseWrapper
import datadog.trace.instrumentation.openai_java.HttpStreamResponseWrapper

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.BiConsumer

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

class OpenAiCallbackExclusionTest extends InstrumentationSpecification {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(LlmObsConfig.LLMOBS_ENABLED, "true")
  }

  def "#kind wrapper keeps header capture but skips error capture: completed=#completed failure=#failure"() {
    setup:
    def source = new CompletableFuture<HttpResponseFor>()
    def cause = new IllegalArgumentException("request failed")
    def responseHeaders = Headers.builder().put("openai-organization", "test-org").build()
    AgentSpan headerParent = null
    int closes = 0
    def response = Stub(HttpResponseFor) {
      headers() >> {
        headerParent = activeSpan()
        responseHeaders
      }
      close() >> { closes++ }
    }
    if (completed) {
      complete(source, response, failure ? cause : null)
    }
    def span = startSpan("test", "openai.request")
    def application = startSpan("test", "application")
    def callback = new ApplicationCallback()
    BiConsumer decorate = { ignoredSpan, ignoredResult -> } as BiConsumer
    def scope = activateSpan(span)
    int before = captures()

    when:
    def dependent = kind == "response" ? HttpResponseWrapper.wrapFuture(source, span, decorate) :
      HttpStreamResponseWrapper.wrapFuture(source, span, decorate)
    int wrapperCaptures = captures() - before
    scope.close()
    scope = activateSpan(application)
    before = captures()
    def downstream = dependent.whenComplete(callback)
    int applicationCaptures = captures() - before
    scope.close()
    scope = null
    complete(source, response, failure ? cause : null)

    then:
    wrapperCaptures == (completed ? 0 : 1)
    applicationCaptures == (completed ? 0 : 1)
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
      assert headerParent == null
      assert closes == 0
      assert span.isError()
      assert span.getTag("error.stack").contains("request failed")
    } else {
      def wrapped = downstream.join()
      assert kind == "response" ? wrapped instanceof HttpResponseWrapper : wrapped instanceof HttpStreamResponseWrapper
      assert headerParent.is(span)
      assert TEST_WRITER.size() == 0
      wrapped.close()
      assert closes == 1
      assert !span.isError()
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER[0].size() == 1
    activeSpan() == null

    cleanup:
    scope?.close()
    complete(source, response, failure ? cause : null)
    application.finish()
    span.finish()
    TEST_WRITER.waitForTraces(2)
    assert TEST_WRITER.collectMany { it }.size() == 3

    where:
    [kind, completed, failure] << [["response", "stream"], [false, true], [false, true]].combinations()
  }

  private static int captures() {
    ScopeDiagnostics.report().records().size()
  }

  private static void complete(CompletableFuture source, Object response, Throwable failure) {
    if (failure == null) {
      source.complete(response)
    } else {
      source.completeExceptionally(failure)
    }
  }

  private static class ApplicationCallback implements BiConsumer<Object, Throwable> {
    int calls
    AgentSpan parent
    AgentSpan child

    @Override
    void accept(Object value, Throwable failure) {
      calls++
      parent = activeSpan()
      child = startSpan("test", "application.callback")
      child.finish()
    }
  }
}
