import static datadog.trace.api.gateway.Events.EVENTS

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import com.amazonaws.services.lambda.runtime.Context
import java.nio.charset.StandardCharsets
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

abstract class LambdaHandlerInstrumentationTest extends VersionedNamingTestBase {
  def requestId = "test-request-id"

  // Must set this env var before the Datadog integration is initialized.
  // If present at load time, the integration auto-enables.
  static {
    environmentVariables.set("_HANDLER", "Handler")
  }

  @Override
  String service() {
    null
  }

  def ig
  def appSecStarted = false
  def capturedMethod = null
  def capturedPath = null
  def capturedHeaders = [:]
  def capturedBody = null
  def appSecEnded = false

  def setup() {
    ig = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC)
    ActiveSubsystems.APPSEC_ACTIVE = true
    appSecStarted = false
    capturedMethod = null
    capturedPath = null
    capturedHeaders = [:]
    capturedBody = null
    appSecEnded = false
    ig.registerCallback(EVENTS.requestStarted(), {
      appSecStarted = true
      new Flow.ResultFlow(new Object())
    } as Supplier)
    ig.registerCallback(EVENTS.requestMethodUriRaw(), { RequestContext ctx, String method, URIDataAdapter uri ->
      capturedMethod = method
      capturedPath = uri.path()
      Flow.ResultFlow.empty()
    } as TriFunction)
    ig.registerCallback(EVENTS.requestHeader(), { RequestContext ctx, String name, String value ->
      capturedHeaders[name] = value
    } as TriConsumer)
    ig.registerCallback(EVENTS.requestHeaderDone(), { RequestContext ctx ->
      Flow.ResultFlow.empty()
    } as Function)
    ig.registerCallback(EVENTS.requestBodyProcessed(), { RequestContext ctx, Object body ->
      capturedBody = body
      Flow.ResultFlow.empty()
    } as BiFunction)
    ig.registerCallback(EVENTS.requestEnded(), { RequestContext ctx, Object spanInfo ->
      appSecEnded = true
      Flow.ResultFlow.empty()
    } as BiFunction)
  }

  def cleanup() {
    ig.reset()
    ActiveSubsystems.APPSEC_ACTIVE = false
  }

  def "test lambda streaming handler"() {
    when:
    def input = new ByteArrayInputStream(StandardCharsets.UTF_8.encode("Hello").array())
    def output = new ByteArrayOutputStream()
    def ctx = Stub(Context) {
      getAwsRequestId() >> requestId
    }
    new HandlerStreaming().handleRequest(input, output, ctx)

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored false
        }
      }
    }
  }

  def "test streaming handler with error"() {
    when:
    def input = new ByteArrayInputStream(StandardCharsets.UTF_8.encode("Hello").array())
    def output = new ByteArrayOutputStream()
    def ctx = Stub(Context) {
      getAwsRequestId() >> requestId
    }
    new HandlerStreamingWithError().handleRequest(input, output, ctx)

    then:
    thrown(Error)
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored true
          tags {
            tag "request_id", requestId
            tag "error.type", "java.lang.Error"
            tag "error.message", "Some error"
            tag "error.stack", String
            tag "language", "jvm"
            tag "process_id", Long
            tag "runtime-id", String
            tag "thread.id", Long
            tag "thread.name", String
            tag "_dd.profiling.ctx", "test"
            tag "_dd.profiling.enabled", 0
            tag "_dd.agent_psr", 1.0
            tag "_dd.tracer_host", String
            tag "_sample_rate", 1
            tag "_dd.trace_span_attribute_schema", { it != null }
          }
        }
      }
    }
  }

  def "appsec callbacks are invoked for API Gateway v1 event"() {
    given:
    def eventJson = """{
      "path": "/api/users/123",
      "headers": {"content-type": "application/json", "x-forwarded-for": "203.0.113.1"},
      "body": "{\\"key\\": \\"value\\"}",
      "requestContext": {
        "httpMethod": "GET",
        "requestId": "req-abc",
        "identity": {"sourceIp": "203.0.113.1"}
      }
    }"""

    when:
    def input = new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8))
    def output = new ByteArrayOutputStream()
    def ctx = Stub(Context) { getAwsRequestId() >> requestId }
    new HandlerStreaming().handleRequest(input, output, ctx)

    then:
    appSecStarted
    capturedMethod == "GET"
    capturedPath == "/api/users/123"
    capturedHeaders["content-type"] == "application/json"
    capturedBody instanceof Map
    appSecEnded
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored false
        }
      }
    }
  }

  def "appsec callbacks are invoked for API Gateway v2 HTTP event"() {
    given:
    def eventJson = """{
      "version": "2.0",
      "headers": {"content-type": "application/json", "accept": "application/json"},
      "cookies": ["session=abc123"],
      "body": "{\\"key\\": \\"value\\"}",
      "requestContext": {
        "http": {
          "method": "POST",
          "path": "/api/items",
          "sourceIp": "198.51.100.1"
        },
        "domainName": "api.example.com"
      }
    }"""

    when:
    def input = new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8))
    def output = new ByteArrayOutputStream()
    def ctx = Stub(Context) { getAwsRequestId() >> requestId }
    new HandlerStreaming().handleRequest(input, output, ctx)

    then:
    appSecStarted
    capturedMethod == "POST"
    capturedPath == "/api/items"
    capturedHeaders["content-type"] == "application/json"
    capturedHeaders["cookie"] == "session=abc123"
    capturedBody instanceof Map
    appSecEnded
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored false
        }
      }
    }
  }

  def "appsec callbacks are not invoked when appsec is disabled"() {
    given:
    ActiveSubsystems.APPSEC_ACTIVE = false

    when:
    def eventJson = """{
      "path": "/api/test",
      "requestContext": {"httpMethod": "GET", "requestId": "req-xyz"}
    }"""
    def input = new ByteArrayInputStream(eventJson.getBytes(StandardCharsets.UTF_8))
    def output = new ByteArrayOutputStream()
    def ctx = Stub(Context) { getAwsRequestId() >> requestId }
    new HandlerStreaming().handleRequest(input, output, ctx)

    then:
    !appSecStarted
    capturedMethod == null
    !appSecEnded
    assertTraces(1) {
      trace(1) {
        span {
          operationName operation()
          spanType DDSpanTypes.SERVERLESS
          errored false
        }
      }
    }
  }
}


class LambdaHandlerInstrumentationV0Test extends LambdaHandlerInstrumentationTest {
  @Override
  int version() {
    0
  }

  @Override
  String operation() {
    "dd-tracer-serverless-span"
  }
}

class LambdaHandlerInstrumentationV1ForkedTest extends LambdaHandlerInstrumentationTest {
  @Override
  int version() {
    1
  }

  @Override
  String operation() {
    "aws.lambda.invoke"
  }
}
