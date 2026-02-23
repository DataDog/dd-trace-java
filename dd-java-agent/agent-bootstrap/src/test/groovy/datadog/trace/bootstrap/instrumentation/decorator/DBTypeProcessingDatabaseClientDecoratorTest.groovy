package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import spock.lang.Ignore

class DBTypeProcessingDatabaseClientDecoratorTest extends ClientDecoratorTest {

  def span = Mock(AgentSpan)

  @Ignore("https://github.com/DataDog/dd-trace-java/pull/5213")
  def "test afterStart"() {
    setup:
    def decorator = newDecorator((String) serviceName)

    when:
    decorator.afterStart(span)

    then:
    if (serviceName != null) {
      1 * span.setServiceName(serviceName, "test-component")
    }
    1 * span.setMeasured(true)
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.context() >> spanContext
    1 * spanContext.setIntegrationName("test-component")
    1 * span.setTag(Tags.SPAN_KIND, "client")
    1 * span.setSpanType("test-type")
    1 * span.setServiceName("test-db")
    1 * span.setOperationName(UTF8BytesString.create("test-db.query"))
    1 * span.setTag("db.type", "test-db") // is this really necessary or a waste of space and cycles?
    1 * span.setMetric(DDTags.ANALYTICS_SAMPLE_RATE, 1.0)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  @Override
  def newDecorator(String serviceName = "test-service") {
    return new DBTypeProcessingDatabaseClientDecorator<Map>() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
        }

        @Override
        protected String service() {
          return serviceName
        }

        @Override
        protected CharSequence component() {
          return "test-component"
        }

        @Override
        protected CharSequence spanType() {
          return "test-type"
        }

        @Override
        protected String dbType() {
          return "test-db"
        }

        @Override
        protected String dbUser(Map map) {
          return map.user
        }

        @Override
        protected String dbInstance(Map map) {
          return map.instance
        }

        @Override
        protected String dbHostname(Map map) {
          return map.hostname
        }

        protected boolean traceAnalyticsDefault() {
          return true
        }
      }
  }
}
