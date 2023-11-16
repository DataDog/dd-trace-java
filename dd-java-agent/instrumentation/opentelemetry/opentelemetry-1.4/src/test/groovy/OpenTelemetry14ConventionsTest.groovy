import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDTags
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.ThreadLocalContextStorage
import spock.lang.Subject

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.SPAN_KIND_INTERNAL
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.toSpanKindTagValue
import static io.opentelemetry.api.trace.SpanKind.CLIENT
import static io.opentelemetry.api.trace.SpanKind.CONSUMER
import static io.opentelemetry.api.trace.SpanKind.INTERNAL
import static io.opentelemetry.api.trace.SpanKind.PRODUCER
import static io.opentelemetry.api.trace.SpanKind.SERVER

class OpenTelemetry14ConventionsTest extends AgentTestRunner {
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
            attributes.forEach { key, value->
              tag(key, value)
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
    when:
    tracer.spanBuilder("some-name")
      .setAttribute("service.name", "my-service")
      .setAttribute("resource.name", "/my-resource")
      .setAttribute("span.type", "http")
      .startSpan()
      .end()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "internal"
          resourceName "/my-resource"
          serviceName "my-service"
          spanType "http"
          tags {
            defaultTags()
            "$SPAN_KIND" "$SPAN_KIND_INTERNAL"
          }
        }
      }
    }
  }

  def "test span analytics.event specific tag"() {
    when:
    tracer.spanBuilder("some-name")
      .setAttribute("analytics.event", value)
      .startSpan()
      .end()


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
              "analytics.event" value
              "$DDTags.ANALYTICS_SAMPLE_RATE" expectedMetric
            }
          }
        }
      }
    }

    where:
    value            | expectedMetric
    true             | 1
    Boolean.TRUE     | 1
    false            | 0
    Boolean.FALSE    | 0
    null             | 0 // Not used
    "true"           | 1
    "false"          | 0
    "TRUE"           | 1
    "something-else" | 0
    ""               | 0
  }

  @Override
  void cleanup() {
    // Test for context leak
    assert Context.current() == Context.root()
    // Safely reset OTel context storage
    ThreadLocalContextStorage.THREAD_LOCAL_STORAGE.remove()
  }
}
