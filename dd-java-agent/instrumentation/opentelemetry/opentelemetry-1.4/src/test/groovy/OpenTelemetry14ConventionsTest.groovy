import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.ThreadLocalContextStorage
import spock.lang.Subject

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND
import static datadog.opentelemetry.shim.trace.OtelConventions.OPERATION_NAME_SPECIFIC_ATTRIBUTE
import static datadog.opentelemetry.shim.trace.OtelConventions.SPAN_KIND_INTERNAL
import static datadog.opentelemetry.shim.trace.OtelConventions.toSpanKindTagValue
import static io.opentelemetry.api.common.AttributeKey.longKey
import static io.opentelemetry.api.common.AttributeKey.stringKey
import static io.opentelemetry.api.trace.SpanKind.CLIENT
import static io.opentelemetry.api.trace.SpanKind.CONSUMER
import static io.opentelemetry.api.trace.SpanKind.INTERNAL
import static io.opentelemetry.api.trace.SpanKind.PRODUCER
import static io.opentelemetry.api.trace.SpanKind.SERVER

class OpenTelemetry14ConventionsTest extends InstrumentationSpecification {
  @Subject
  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("conventions")

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
  }

  def "test span name conventions"() {
    when:
    def builder = tracer.spanBuilder("some-name")
      .setSpanKind(kind)
    attributes.forEach { key, value -> builder.setAttribute(key, value) }
    builder.startSpan()
      .end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "$expectedOperationName"
          resourceName "some-name"
          tags {
            defaultTags()
            "$SPAN_KIND" "${toSpanKindTagValue(kind == null ? INTERNAL : kind)}"
            attributes.forEach { key, value ->
              if (!OPERATION_NAME_SPECIFIC_ATTRIBUTE.equals(key)) {
                tag(key, value)
              }
            }
          }
        }
      }
    }

    where:
    kind     | attributes                                                                     | expectedOperationName
    // Fallback behavior
    null     | [:]                                                                            | "internal"
    // Internal spans
    INTERNAL | [:]                                                                            | "internal"
    // Server spans
    SERVER   | [:]                                                                            | "server.request"
    SERVER   | ["http.request.method": "GET"]                                                 | "http.server.request"
    SERVER   | ["http.request.method": "GET"]                                                 | "http.server.request"
    SERVER   | ["network.protocol.name": "amqp"]                                              | "amqp.server.request"
    // Client spans
    CLIENT   | [:]                                                                            | "client.request"
    CLIENT   | ["http.request.method": "GET"]                                                 | "http.client.request"
    CLIENT   | ["db.system": "mysql"]                                                         | "mysql.query"
    CLIENT   | ["network.protocol.name": "amqp"]                                              | "amqp.client.request"
    CLIENT   | ["network.protocol.name": "AMQP"]                                              | "amqp.client.request"
    // Messaging spans
    PRODUCER | [:]                                                                            | "producer"
    CONSUMER | [:]                                                                            | "consumer"
    CONSUMER | ["messaging.system": "rabbitmq", "messaging.operation": "publish"]             | "rabbitmq.publish"
    PRODUCER | ["messaging.system": "rabbitmq", "messaging.operation": "publish"]             | "rabbitmq.publish"
    CLIENT   | ["messaging.system": "rabbitmq", "messaging.operation": "publish"]             | "rabbitmq.publish"
    SERVER   | ["messaging.system": "rabbitmq", "messaging.operation": "publish"]             | "rabbitmq.publish"
    // RPC spans
    CLIENT   | ["rpc.system": "grpc"]                                                         | "grpc.client.request"
    SERVER   | ["rpc.system": "grpc"]                                                         | "grpc.server.request"
    CLIENT   | ["rpc.system": "aws-api"]                                                      | "aws.client.request"
    CLIENT   | ["rpc.system": "aws-api", "rpc.service": "helloworld"]                         | "aws.helloworld.request"
    SERVER   | ["rpc.system": "aws-api"]                                                      | "aws-api.server.request"
    // FAAS spans
    CLIENT   | ["faas.invoked_provider": "alibaba_cloud", "faas.invoked_name": "my-function"] | "alibaba_cloud.my-function.invoke"
    SERVER   | ["faas.trigger": "datasource"]                                                 | "datasource.invoke"
    // GraphQL spans
    INTERNAL | ["graphql.operation.type": "query"]                                            | "graphql.server.request"
    null     | ["graphql.operation.type": "query"]                                            | "graphql.server.request"
    // User override
    CLIENT   | ["db.system": "mysql", "operation.name": "db.query"]                           | "db.query"
    CLIENT   | ["db.system": "mysql", "operation.name": "DB.query"]                           | "db.query"
  }

  def "test span specific tags"() {
    setup:
    def builder = tracer.spanBuilder("some-name")
    def keyFor = (String key) -> useAttributeKey ? stringKey(key) : key

    when:
    if (setInBuilder) {
      builder.setAttribute(keyFor("operation.name"), "my-operation")
      .setAttribute(keyFor("service.name"), "my-service")
      .setAttribute(keyFor("resource.name"), "/my-resource")
      .setAttribute(keyFor("span.type"), "http")
    }
    def result = builder.startSpan()
    if (!setInBuilder) {
      result.setAttribute(keyFor("operation.name"), "my-operation")
      .setAttribute(keyFor("service.name"), "my-service")
      .setAttribute(keyFor("resource.name"), "/my-resource")
      .setAttribute(keyFor("span.type"), "http")
    }
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "my-operation"
          resourceName "/my-resource"
          serviceName "my-service"
          spanType "http"
          tags {
            serviceNameSource null //service name was manually set
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
          }
        }
      }
    }

    where:
    setInBuilder | useAttributeKey
    true         | true
    true         | false
    false        | true
    false        | false
  }

  def "test span analytics.event specific tag"() {
    setup:
    def builder = tracer.spanBuilder("some-name")

    when:
    if (setInBuilder) {
      builder.setAttribute("analytics.event", value)
    }
    def result = builder.startSpan()
    if (!setInBuilder) {
      result.setAttribute("analytics.event", value)
    }
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "internal"
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            if (value != null) {
              "$DDTags.ANALYTICS_SAMPLE_RATE" expectedMetric
            }
          }
        }
      }
    }

    where:
    setInBuilder | value            | expectedMetric
    true         | true             | 1
    true         | Boolean.TRUE     | 1
    true         | false            | 0
    true         | Boolean.FALSE    | 0
    true         | null             | 0 // Not used
    true         | "true"           | 1
    true         | "false"          | 0
    true         | "TRUE"           | 1
    true         | "something-else" | 0
    true         | ""               | 0
    false        | true             | 1
    false        | Boolean.TRUE     | 1
    false        | false            | 0
    false        | Boolean.FALSE    | 0
    false        | null             | 0 // Not used
    false        | "true"           | 1
    false        | "false"          | 0
    false        | "TRUE"           | 1
    false        | "something-else" | 0
    false        | ""               | 0
  }

  def "test span http.response.status_code specific tag"() {
    setup:
    def builder = tracer.spanBuilder("some-name")

    when:
    if (setInBuilder) {
      if (attributeKey) {
        builder.setAttribute(longKey("http.response.status_code"), value)
      } else {
        builder.setAttribute("http.response.status_code", value)
      }
    }
    def result = builder.startSpan()
    if (!setInBuilder) {
      if (attributeKey) {
        result.setAttribute(longKey("http.response.status_code"), value)
      } else {
        result.setAttribute("http.response.status_code", value)
      }
    }
    result.end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "internal"
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
            if (value != null) {
              "$Tags.HTTP_STATUS" expectedStatus
            }
          }
        }
      }
    }

    where:
    setInBuilder | attributeKey | value       | expectedStatus
    true         | false        | null        | 0 // Not used
    true         | false        | 200         | 200
    true         | false        | 404L        | 404
    true         | false        | 500 as Long | 500
    false        | false        | null        | 0 // Not used
    false        | false        | 200         | 200
    false        | false        | 404L        | 404
    false        | false        | 500 as Long | 500
    true         | true         | null        | 0 // Not used
    true         | true         | 200         | 200
    true         | true         | 404L        | 404
    true         | true         | 500 as Long | 500
    false        | true         | null        | 0 // Not used
    false        | true         | 200         | 200
    false        | true         | 404L        | 404
    false        | true         | 500 as Long | 500
  }

  @Override
  void cleanup() {
    // Test for context leak
    assert Context.current() == Context.root()
    // Safely reset OTel context storage
    ThreadLocalContextStorage.THREAD_LOCAL_STORAGE.remove()
  }
}
