package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags

class DBTypeProcessingDatabaseClientDecoratorTest extends ClientDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator((String) serviceName)

    when:
    decorator.afterStart(span)

    then:
    if (serviceName != null) {
      1 * span.setTag(DDTags.SERVICE_NAME, serviceName)
    }
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.setTag(Tags.SPAN_KIND, "client")
    1 * span.setTag(DDTags.SPAN_TYPE, "test-type")
    1 * span.setServiceName("test-db")
    1 * span.setOperationName("test-db.query")
    1 * span.setTag("db.type", "test-db") // is this really necessary or a waste of space and cycles?
    1 * span.setTag(DDTags.ANALYTICS_SAMPLE_RATE, 1.0)
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
      protected String component() {
        return "test-component"
      }

      @Override
      protected String spanType() {
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

      protected boolean traceAnalyticsDefault() {
        return true
      }
    }
  }
}
