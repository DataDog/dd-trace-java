package datadog.trace.instrumentation.httpclient

import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.CallDepthThreadLocalMap
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

/** Forked so AppSec configuration is applied before instrumentation is installed. */
class JavaHttpClientBlockingForkedTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('appsec.enabled', 'true')
    injectSysConfig('appsec.rasp.enabled', 'true')
  }

  def 'blocking restores the parent and finishes each client span'() {
    given:
    def subscription = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)
    def blockedSpans = []
    def flow = Stub(Flow) {
      getAction() >> new Flow.Action.RequestBlockingAction(403, BlockingContentType.JSON)
    }
    subscription.registerCallback(EVENTS.httpClientRequest(), { ctx, clientRequest ->
      blockedSpans.add(AgentTracer.activeSpan())
      flow
    } as BiFunction)
    def parent = TEST_TRACER.startSpan('test', 'parent',
      new TagContext().withRequestContextDataAppSec(new Object()))
    def parentScope = AgentTracer.activateSpan(parent)
    def client = HttpClient.newHttpClient()
    def request = HttpRequest.newBuilder(URI.create('http://localhost:1/blocked')).build()

    when:
    2.times {
      try {
        if (async) {
          client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
        } else {
          client.send(request, HttpResponse.BodyHandlers.discarding())
        }
        assert false: 'The request must be blocked before reaching the client'
      } catch (BlockingException expected) {
        assert AgentTracer.activeSpan().is(parent)
        assert CallDepthThreadLocalMap.getCallDepth(HttpClient) == 0
        assert !JavaNetClientDecorator.DECORATE.isContextInjectionAllowed()
        assert blockedSpans.last().finished
      }
    }
    parentScope.close()
    parentScope = null
    parent.finish()

    then:
    blockedSpans.size() == 2
    blockedSpans.every { it.parentId == parent.spanId }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER[0].size() == 3

    cleanup:
    parentScope?.close()
    if (parent != null && !parent.finished) {
      parent.finish()
    }
    subscription.reset()

    where:
    async << [false, true]
  }
}
