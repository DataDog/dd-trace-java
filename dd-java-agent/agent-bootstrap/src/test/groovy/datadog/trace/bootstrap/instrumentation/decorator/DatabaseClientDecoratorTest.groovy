package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.Tags

import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_HOST
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX

class DatabaseClientDecoratorTest extends ClientDecoratorTest {

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    setup:
    def decorator = newDecorator((String) serviceName)
    def spanContext = Mock(AgentSpanContext)

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
    1 * span.setMetric(DDTags.ANALYTICS_SAMPLE_RATE, 1.0)
    0 * _

    where:
    serviceName << ["test-service", "other-service", null]
  }

  def "test onConnection"() {
    setup:
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameByInstance")
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX, "$instanceTypeSuffix")
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_HOST, "$renameByHost")
    def decorator = newDecorator()

    when:
    decorator.onConnection(span, session)

    then:
    if (session) {
      1 * span.setTag(Tags.DB_USER, session.user)
      if (session.instance != null) {
        1 * span.setTag(Tags.DB_INSTANCE, session.instance)
      }
      if (session.hostname != null) {
        1 * span.setTag(Tags.PEER_HOSTNAME, session.hostname)
      }
      if (instanceTypeSuffix && renameByInstance && session.instance) {
        1 * span.setServiceName(session.instance + "-" + decorator.dbType(), _)
      } else if (renameByInstance && session.instance) {
        1 * span.setServiceName(session.instance, _)
      } else if (renameByHost) {
        1 * span.setServiceName(session.hostname, _)
      }
    }
    0 * _

    where:
    renameByInstance | instanceTypeSuffix | renameByHost  | session
    false            | false              | false         | null
    true             | false              | false         | [user: "test-user", hostname: "test-hostname"]
    false            | false              | false         | [instance: "test-instance", hostname: "test-hostname"]
    true             | false              | false         | [user: "test-user", instance: "test-instance"]
    false            | true               | false         | null
    true             | true               | false         | [user: "test-user", hostname: "test-hostname"]
    false            | true               | false         | [instance: "test-instance", hostname: "test-hostname"]
    true             | true               | false         | [user: "test-user", instance: "test-instance"]
    false            | false              | true          | null
    true             | false              | true          | [user: "test-user", hostname: "test-hostname"]
    false            | false              | true          | [instance: "test-instance", hostname: "test-hostname"]
    true             | false              | true          | [user: "test-user", instance: "test-instance"]
    false            | true               | true          | null
    true             | true               | true          | [user: "test-user", hostname: "test-hostname"]
    false            | true               | true          | [instance: "test-instance", hostname: "test-hostname"]
    true             | true               | true          | [user: "test-user", instance: "test-instance"]
  }

  def "test onStatement"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.onStatement(span, statement)

    then:
    1 * span.setResourceName(statement)
    0 * _

    where:
    statement      | _
    null           | _
    ""             | _
    "db-statement" | _
  }

  @Override
  def newDecorator(String serviceName = "test-service") {
    return new DatabaseClientDecorator<Map>() {
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
