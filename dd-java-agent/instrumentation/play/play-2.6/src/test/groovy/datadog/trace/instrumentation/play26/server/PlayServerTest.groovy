package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.instrumentation.play26.PlayHttpServerDecorator

class PlayServerTest extends AbstractPlayServerTest {
  // Disabling involving Appsec / Instrumentation Gateway, they are tested in play-appsec-2.x modules

  @Override
  boolean testBlocking() {
    false
  }

  @Override
  boolean testBlockingOnResponse() {
    false
  }

  @Override
  boolean testRequestBody() {
    false
  }

  @Override
  boolean testBodyJson() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    false
  }

  @Override
  boolean testBodyMultipart() {
    false
  }

  @Override
  boolean testResponseBodyJson() {
    false
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    null
  }

  @Override
  boolean testBodyXml() {
    false
  }
}

class PlayServerOtelSemanticsForkedTest extends PlayServerTest implements TestingOtelHttpSemantics {
}

class PlayRoutePropagationDatadogSemanticsForkedTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('trace.otel.semantics.enabled', 'false')
  }

  def "route propagation does not touch spans with Datadog semantics"() {
    setup:
    def playSpan = Mock(AgentSpan)
    def transportSpan = Mock(AgentSpan)

    when:
    PlayHttpServerDecorator.DECORATE.propagateRoute(playSpan, transportSpan)

    then:
    0 * playSpan._
    0 * transportSpan._
  }
}
