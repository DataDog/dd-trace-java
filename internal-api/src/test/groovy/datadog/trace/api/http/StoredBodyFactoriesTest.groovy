package datadog.trace.api.http

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

import java.util.function.BiFunction
import java.util.function.Supplier

import static datadog.trace.api.gateway.Events.EVENTS

class StoredBodyFactoriesTest extends DDSpecification {
  AgentTracer.TracerAPI originalTracer

  AgentTracer.TracerAPI tracerAPI = Mock()
  AgentSpan agentSpan

  RequestContext requestContext = Mock(RequestContext)
  CallbackProvider cbp = Mock()

  def setup() {
    originalTracer = AgentTracer.provider
    AgentTracer.provider = tracerAPI
    _ * tracerAPI.activeSpan() >> { agentSpan }
    _ * tracerAPI.getCallbackProvider(RequestContextSlot.APPSEC) >> cbp
  }

  def cleanup() {
    AgentTracer.provider = originalTracer
  }

  void 'no active span'() {
    expect:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null
    StoredBodyFactories.maybeDeliverBodyInOneGo('', requestContext).is(Flow.ResultFlow.empty())
  }

  void 'no active context'() {
    agentSpan = Mock()

    when:
    StoredByteBody sbb1 = StoredBodyFactories.maybeCreateForByte(null, null)
    StoredByteBody sbb2 = StoredBodyFactories.maybeCreateForChar(null)
    Flow<Void> flow = StoredBodyFactories.maybeDeliverBodyInOneGo('', requestContext)

    then:
    2 * agentSpan.requestContext >> null
    sbb1 == null
    sbb2 == null
    flow.is(Flow.ResultFlow.empty())
  }

  void 'no IG callbacks'() {
    expect:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null
    StoredBodyFactories.maybeDeliverBodyInOneGo('', requestContext).is(Flow.ResultFlow.empty())
  }

  void 'everything needed provided'() {
    agentSpan = Mock()
    def mockRequestBodyStart = Mock(BiFunction)
    def mockRequestBodyDone = Mock(BiFunction)
    StoredBodySupplier bodySupplier1, bodySupplier2
    Flow mockFlow = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, null) != null
    StoredBodyFactories.maybeCreateForChar(null) != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * cbp.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * cbp.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)

    when:
    Flow f = StoredBodyFactories.maybeDeliverBodyInOneGo({ 'body' } as Supplier<CharSequence>, requestContext)

    then:
    1 * cbp.getCallback(EVENTS.requestBodyStart()) >> mockRequestBodyStart
    1 * cbp.getCallback(EVENTS.requestBodyDone()) >> mockRequestBodyDone
    1 * mockRequestBodyStart.apply(requestContext, _ as StoredBodySupplier) >> {
      bodySupplier1 = it[1]
      null
    }
    1 * mockRequestBodyDone.apply(requestContext, _ as StoredBodySupplier) >> {
      bodySupplier2 = it[1]
      mockFlow
    }
    bodySupplier1.is(bodySupplier2)
    bodySupplier2.get() == 'body'
    f.is(mockFlow)
  }

  void 'everything needed provided delivery in one go string variant'() {
    agentSpan = Mock()
    def mockRequestBodyStart = Mock(BiFunction)
    def mockRequestBodyDone = Mock(BiFunction)
    StoredBodySupplier bodySupplier
    Flow mockFlow = Mock()

    when:
    Flow f = StoredBodyFactories.maybeDeliverBodyInOneGo('body', requestContext)

    then:
    1 * cbp.getCallback(EVENTS.requestBodyStart()) >> mockRequestBodyStart
    1 * cbp.getCallback(EVENTS.requestBodyDone()) >> mockRequestBodyDone
    1 * mockRequestBodyDone.apply(requestContext, _ as StoredBodySupplier) >> {
      bodySupplier = it[1]
      mockFlow
    }
    bodySupplier.get() == 'body'
    f.is(mockFlow)
  }

  void 'with correct content length'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, '1') != null
    StoredBodyFactories.maybeCreateForChar('1') != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * cbp.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * cbp.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)
  }

  void 'with correct content length int version'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, 1) != null
    StoredBodyFactories.maybeCreateForChar(1) != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * cbp.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * cbp.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)
  }

  void 'with bad content length'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, 'foo') != null
    StoredBodyFactories.maybeCreateForChar('foo') != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * cbp.getCallback(EVENTS.requestBodyStart()) >> Mock(BiFunction)
    2 * cbp.getCallback(EVENTS.requestBodyDone()) >> Mock(BiFunction)
  }
}
