package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class ClientDecoratorTest extends BaseDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator((String) serviceName)
    def recordingSpan = new RecordingSpan()

    when:
    decorator.afterStart(recordingSpan)

    then:
    def expected = ExpectedSpanState.expected()
      .spanType(decorator.spanType())
      .component("test-component")
      .spanKind("client")
      .measured(true)
      .analyticsSampleRate(1.0d)
    if (serviceName != null) {
      expected.serviceName(serviceName, "test-component")
    }
    // Polymorphic parent spec: subclass decorators (e.g. DB-type processing) layer on extra tags in
    // afterStart, so tolerate additional tags while asserting the client-level scalars exactly.
    expected.assertAppliedAllowingExtraTags(recordingSpan)

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test beforeFinish"() {
    when:
    newDecorator("test-service").beforeFinish(span)

    then:
    0 * _
  }

  @Override
  def newDecorator() {
    return newDecorator("test-service")
  }

  def newDecorator(String serviceName) {
    return new ClientDecorator() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
        }

        @Override
        protected String service() {
          return serviceName
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
