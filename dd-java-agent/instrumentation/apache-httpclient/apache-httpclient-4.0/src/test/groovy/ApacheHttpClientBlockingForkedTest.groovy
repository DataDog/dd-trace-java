import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.CallDepthThreadLocalMap
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import org.apache.http.HttpHost
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.message.BasicHttpRequest

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

/** Forked so AppSec configuration is applied before instrumentation is installed. */
class ApacheHttpClientBlockingForkedTest extends InstrumentationSpecification {
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
    subscription.registerCallback(EVENTS.httpClientRequest(), { ctx, request ->
      blockedSpans.add(AgentTracer.activeSpan())
      flow
    } as BiFunction)
    def parent = TEST_TRACER.startSpan('test', 'parent',
      new TagContext().withRequestContextDataAppSec(new Object()))
    def parentScope = AgentTracer.activateSpan(parent)
    def client = new DefaultHttpClient()

    when:
    2.times {
      try {
        if (hostRequest) {
          client.execute(new HttpHost('localhost', 1), new BasicHttpRequest('GET', '/blocked'))
        } else {
          client.execute(new HttpGet('http://localhost:1/blocked'))
        }
        assert false: 'The request must be blocked before reaching the client'
      } catch (BlockingException expected) {
        assert AgentTracer.activeSpan().is(parent)
        assert CallDepthThreadLocalMap.getCallDepth(HttpClient) == 0
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
    hostRequest << [false, true]
  }
}
