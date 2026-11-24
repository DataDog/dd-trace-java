package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import playtest.ControlledAction

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

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

class PlayResponseCompletionTest extends InstrumentationSpecification {
  def "response waits for Play span completion (status=#status, completed=#completed)"() {
    setup:
    def action = new ControlledAction(status, completed)
    def root = startSpan("test", "server.request")
    def scope = root.attachWithContext()
    def response

    when:
    try {
      response = action.invoke()
    } finally {
      scope.close()
    }
    action.complete()

    then:
    activeSpan() == null
    action.span.operationName.toString() == "play.request"
    !action.span.finished
    // A completed action must not let the server respond while its Play callback is queued.
    !response.isCompleted()

    when:
    action.drain()

    then:
    action.span.finished
    response.isCompleted()
    response.value().get() == action.completion
    action.span.isError() == (status == 0)
    if (status == 0) {
      assert action.span.getTag("error.message") == "action failed"
    }

    when:
    root.finish()

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 3
    action.span.parentId == root.spanId
    trace.find { it.operationName.toString() == "action" }.parentId == action.span.spanId

    cleanup:
    action.drain()
    root.finish()

    where:
    [status, completed] << [[200, 404, 0], [false, true]].combinations()
  }
}
