package datadog.trace.api.http

import datadog.trace.api.function.BiFunction
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.InstrumentationGateway
import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

class StoredBodyFactoriesTest extends DDSpecification {
  AgentTracer.TracerAPI originalTracer

  AgentTracer.TracerAPI tracerAPI = Mock()
  AgentSpan agentSpan

  RequestContext requestContext = Mock()
  InstrumentationGateway ig = Mock()

  def setup() {
    originalTracer = AgentTracer.provider
    AgentTracer.provider = tracerAPI
    _ * tracerAPI.activeSpan() >> { agentSpan }
    _ * tracerAPI.instrumentationGateway() >> ig
  }

  def cleanup() {
    AgentTracer.provider = originalTracer
  }

  void 'no active span'() {
    expect:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null
  }

  void 'no active context'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null

    then:
    2 * agentSpan.requestContext >> null
  }

  void 'no IG callbacks'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, null) == null
    StoredBodyFactories.maybeCreateForChar(null) == null

    then:
    2 * agentSpan.requestContext >> requestContext
  }

  void 'everything needed provided'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, null) != null
    StoredBodyFactories.maybeCreateForChar(null) != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * ig.getCallback(Events.REQUEST_BODY_START) >> Mock(BiFunction)
    2 * ig.getCallback(Events.REQUEST_BODY_DONE) >> Mock(BiFunction)
  }

  void 'with correct content length'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, '1') != null
    StoredBodyFactories.maybeCreateForChar('1') != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * ig.getCallback(Events.REQUEST_BODY_START) >> Mock(BiFunction)
    2 * ig.getCallback(Events.REQUEST_BODY_DONE) >> Mock(BiFunction)
  }

  void 'with bad content length'() {
    agentSpan = Mock()

    when:
    StoredBodyFactories.maybeCreateForByte(null, 'foo') != null
    StoredBodyFactories.maybeCreateForChar('foo') != null

    then:
    2 * agentSpan.requestContext >> requestContext
    2 * ig.getCallback(Events.REQUEST_BODY_START) >> Mock(BiFunction)
    2 * ig.getCallback(Events.REQUEST_BODY_DONE) >> Mock(BiFunction)
  }
}
