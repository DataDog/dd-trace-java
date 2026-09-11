package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class ServerDecoratorTest extends BaseDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator()
    def recordingSpan = new RecordingSpan()

    when:
    decorator.afterStart(recordingSpan)

    then:
    ExpectedSpanState.expected()
      .spanType(decorator.spanType())
      .component("test-component")
      .spanKind("server")
      .language()
      .analyticsSampleRate(decorator.traceAnalyticsEnabled ? 1.0d : null)
      .assertAppliedTo(recordingSpan)
  }

  def "test beforeFinish"() {
    when:
    newDecorator().beforeFinish(span)

    then:
    (0..1) * span.localRootSpan
  }

  @Override
  def newDecorator() {
    return new ServerDecorator() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
        }

        @Override
        protected CharSequence spanType() {
          return "test-type"
        }

        @Override
        protected CharSequence component() {
          return "test-component"
        }

        protected boolean traceAnalyticsDefault() {
          return true
        }
      }
  }
}
