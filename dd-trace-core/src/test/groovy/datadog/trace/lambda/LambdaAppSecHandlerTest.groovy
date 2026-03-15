package datadog.trace.lambda

import datadog.trace.api.Config
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.TagContext
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.api.gateway.Events.EVENTS

class LambdaAppSecHandlerTest extends DDCoreSpecification {

  @Shared
  def originalAppSecActive

  def setupSpec() {
    originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE
  }

  def cleanupSpec() {
    ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive
  }

  def setup() {
    ActiveSubsystems.APPSEC_ACTIVE = true
  }

  def "processRequestStart returns null when AppSec is disabled"() {
    given:
    ActiveSubsystems.APPSEC_ACTIVE = false
    def event = createInputStream('{"test": "data"}')

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result == null
  }

  def "processRequestStart returns null for non-ByteArrayInputStream"() {
    when:
    def result = LambdaAppSecHandler.processRequestStart("not a stream")

    then:
    result == null
  }

  def "processRequestStart returns null for null event"() {
    when:
    def result = LambdaAppSecHandler.processRequestStart(null)

    then:
    result == null
  }

  def "processRequestStart returns null for oversized event"() {
    given:
    def maxSize = Config.get().getAppSecBodyParsingSizeLimit()
    def largeBody = "x" * (maxSize + 1)
    def event = createInputStream(largeBody)

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result == null
  }

  def "processRequestStart returns null for zero-size event"() {
    given:
    def event = createInputStream('')

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result == null
  }

  def "processRequestStart returns null for malformed JSON"() {
    given:
    def event = createInputStream('{invalid json')

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result == null
  }

  def "stream can be read multiple times after processing"() {
    given:
    def jsonData = '{"test": "data", "requestContext": {"httpMethod": "GET"}}'
    def event = createInputStream(jsonData)

    when:
    LambdaAppSecHandler.processRequestStart(event)
    event.reset()
    def content = new String(event.readAllBytes(), StandardCharsets.UTF_8)

    then:
    content == jsonData
  }


  // ============================================================================
  // Trigger Type Detection Tests
  // ============================================================================

  def "detects API Gateway v1 REST trigger type"() {
    given:
    def event = [
      requestContext: [
        httpMethod: "GET",
        requestId: "abc123"
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V1_REST
  }

  def "detects API Gateway v2 HTTP trigger type"() {
    given:
    def event = [
      requestContext: [
        http: [
          method: "POST",
          path: "/api"
        ],
        domainName: "api.example.com"
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_HTTP
  }

  def "detects Lambda Function URL trigger type"() {
    given:
    def event = [
      requestContext: [
        http: [
          method: "GET",
          path: "/"
        ],
        domainName: "xyz123.lambda-url.us-east-1.on.aws"
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.LAMBDA_URL
  }

  def "detects ALB trigger type without multi-value headers"() {
    given:
    def event = [
      httpMethod: "GET",
      path: "/",
      requestContext: [
        elb: [
          targetGroupArn: "arn:aws:..."
        ]
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.ALB
  }

  def "detects ALB trigger type with multi-value headers"() {
    given:
    def event = [
      httpMethod: "GET",
      path: "/",
      multiValueHeaders: [
        accept: ["text/html", "application/json"]
      ],
      requestContext: [
        elb: [
          targetGroupArn: "arn:aws:..."
        ]
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.ALB_MULTI_VALUE
  }

  def "detects WebSocket trigger type with routeKey"() {
    given:
    def event = [
      requestContext: [
        connectionId: "conn-123",
        routeKey: "\$connect"
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET
  }

  def "detects WebSocket trigger type with eventType"() {
    given:
    def event = [
      requestContext: [
        connectionId: "conn-456",
        eventType: "CONNECT"
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.API_GATEWAY_V2_WEBSOCKET
  }

  def "detects unknown trigger type for unrecognized events"() {
    given:
    def event = [
      someUnknownField: "value"
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.UNKNOWN
  }

  def "detects unknown trigger type for empty requestContext"() {
    given:
    def event = [
      requestContext: [:]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.UNKNOWN
  }

  def "detects Lambda URL when http present but no domainName"() {
    given:
    def event = [
      requestContext: [
        http: [
          method: "GET",
          path: "/ambiguous"
        ]
      ]
    ]

    when:
    def triggerType = LambdaAppSecHandler.detectTriggerType(event)

    then:
    triggerType == LambdaAppSecHandler.LambdaTriggerType.LAMBDA_URL
  }

  // ============================================================================
  // Data Extraction Tests with Mocked Callbacks
  // ============================================================================

  def "extracts API Gateway v1 REST data correctly"() {
    given:
    def eventJson = '''
    {
      "path": "/api/users/123",
      "httpMethod": "POST",
      "headers": {
        "Content-Type": "application/json",
        "Authorization": "Bearer token123"
      },
      "pathParameters": {
        "userId": "123"
      },
      "body": "{\\"name\\": \\"John\\"}",
      "requestContext": {
        "httpMethod": "POST",
        "requestId": "req-123",
        "identity": {
          "sourceIp": "192.168.1.100"
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    // Track callback invocations
    def capturedMethod = null
    def capturedPath = null
    def capturedHeaders = [:]
    def capturedSourceIp = null
    def capturedSourcePort = null
    def capturedPathParams = null
    def capturedBody = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedMethod = method
      capturedPath = uri.path()
    },
    onHeader: { name, value ->
      capturedHeaders[name] = value
    },
    onSocketAddress: { ip, port ->
      capturedSourceIp = ip
      capturedSourcePort = port
    },
    onPathParams: { params ->
      capturedPathParams = params
    },
    onBody: { body ->
      capturedBody = body
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    result instanceof TagContext

    capturedMethod == "POST"
    capturedPath == "/api/users/123"
    capturedHeaders["Content-Type"] == "application/json"
    capturedHeaders["Authorization"] == "Bearer token123"
    capturedSourceIp == "192.168.1.100"
    capturedSourcePort == 0
    capturedPathParams == ["userId": "123"]
    capturedBody instanceof Map
    capturedBody.name == "John"
  }

  def "extracts API Gateway v2 HTTP data correctly"() {
    given:
    def eventJson = '''
    {
      "version": "2.0",
      "headers": {
        "content-type": "application/json",
        "x-custom-header": "custom-value"
      },
      "cookies": ["session=abc123", "user=john"],
      "pathParameters": {
        "id": "456"
      },
      "body": "test body",
      "requestContext": {
        "http": {
          "method": "PUT",
          "path": "/api/items/456",
          "sourceIp": "10.0.0.50",
          "sourcePort": 54321
        },
        "domainName": "api.example.com"
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedMethod = null
    def capturedPath = null
    def capturedHeaders = [:]
    def capturedSourceIp = null
    def capturedSourcePort = null
    def capturedPathParams = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedMethod = method
      capturedPath = uri.path()
    },
    onHeader: { name, value ->
      capturedHeaders[name] = value
    },
    onSocketAddress: { ip, port ->
      capturedSourceIp = ip
      capturedSourcePort = port
    },
    onPathParams: { params ->
      capturedPathParams = params
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedMethod == "PUT"
    capturedPath == "/api/items/456"
    capturedHeaders["content-type"] == "application/json"
    capturedHeaders["x-custom-header"] == "custom-value"
    capturedHeaders["cookie"] == "session=abc123; user=john"
    capturedSourceIp == "10.0.0.50"
    capturedSourcePort == 54321
    capturedPathParams == ["id": "456"]
  }

  def "extracts Lambda Function URL data correctly"() {
    given:
    def eventJson = '''
    {
      "version": "2.0",
      "headers": {
        "host": "xyz.lambda-url.us-east-1.on.aws"
      },
      "requestContext": {
        "http": {
          "method": "GET",
          "path": "/function/path",
          "sourceIp": "1.2.3.4"
        },
        "domainName": "xyz.lambda-url.us-east-1.on.aws"
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedMethod = null
    def capturedPath = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedMethod = method
      capturedPath = uri.path()
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedMethod == "GET"
    capturedPath == "/function/path"
  }

  def "extracts ALB data correctly"() {
    given:
    def eventJson = '''
    {
      "path": "/alb/test",
      "httpMethod": "DELETE",
      "headers": {
        "x-forwarded-for": "203.0.113.42",
        "user-agent": "curl/7.64.1"
      },
      "requestContext": {
        "elb": {
          "targetGroupArn": "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/my-target-group/50dc6c495c0c9188"
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedMethod = null
    def capturedPath = null
    def capturedSourceIp = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedMethod = method
      capturedPath = uri.path()
    },
    onSocketAddress: { ip, port ->
      capturedSourceIp = ip
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedMethod == "DELETE"
    capturedPath == "/alb/test"
    capturedSourceIp == "203.0.113.42"
  }

  def "extracts ALB multi-value headers correctly"() {
    given:
    def eventJson = '''
    {
      "path": "/test",
      "httpMethod": "GET",
      "multiValueHeaders": {
        "accept": ["text/html", "application/json"],
        "x-custom": ["value1", "value2"]
      },
      "requestContext": {
        "elb": {
          "targetGroupArn": "arn:aws:..."
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedHeaders = [:]

    setupMockCallbacks(
    onHeader: { name, value ->
      capturedHeaders[name] = value
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedHeaders["accept"] == "text/html, application/json"
    capturedHeaders["x-custom"] == "value1, value2"
  }

  def "handles multi-value headers with empty list"() {
    given:
    def eventJson = '''
    {
      "path": "/test",
      "httpMethod": "GET",
      "multiValueHeaders": {
        "accept": [],
        "x-custom": ["value1"]
      },
      "requestContext": {
        "elb": {
          "targetGroupArn": "arn:aws:..."
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedHeaders = [:]

    setupMockCallbacks(
    onHeader: { name, value ->
      capturedHeaders[name] = value
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedHeaders["accept"] == ""  // Empty list should result in empty string
    capturedHeaders["x-custom"] == "value1"
  }

  def "extracts WebSocket data correctly"() {
    given:
    def eventJson = '''
    {
      "requestContext": {
        "routeKey": "$connect",
        "connectionId": "conn-abc123",
        "identity": {
          "sourceIp": "192.168.0.100"
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedMethod = null
    def capturedPath = null
    def capturedSourceIp = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedMethod = method
      capturedPath = uri.path()
    },
    onSocketAddress: { ip, port ->
      capturedSourceIp = ip
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedMethod == "WEBSOCKET"
    capturedPath == "\$connect"
    capturedSourceIp == "192.168.0.100"
  }

  def "handles base64 encoded body correctly"() {
    given:
    def originalBody = "This is test data"
    def base64Body = Base64.getEncoder().encodeToString(originalBody.getBytes())
    def eventJson = """
    {
      "body": "${base64Body}",
      "isBase64Encoded": true,
      "requestContext": {
        "httpMethod": "POST"
      }
    }
    """
    def event = createInputStream(eventJson)

    def capturedBody = null

    setupMockCallbacks(
    onBody: { body ->
      capturedBody = body
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedBody == originalBody
  }

  def "handles null body correctly"() {
    given:
    def event = createInputStream('{"body": null, "requestContext": {"httpMethod": "GET"}}')

    def capturedBody = "NOT_CALLED"

    setupMockCallbacks(
    onBody: { body ->
      capturedBody = body
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedBody == "NOT_CALLED"  // Callback should not be invoked for null body
  }

  def "handles empty body correctly"() {
    given:
    def event = createInputStream('{"body": "", "requestContext": {"httpMethod": "POST"}}')

    def capturedBody = null

    setupMockCallbacks(
    onBody: { body ->
      capturedBody = body
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedBody == ""  // Empty body is passed as empty string to WAF
  }

  def "handles path with query string correctly"() {
    given:
    def eventJson = '''
    {
      "path": "/api/users?id=123&filter=active",
      "requestContext": {
        "httpMethod": "GET"
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedPath = null
    def capturedQuery = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedPath = uri.path()
      capturedQuery = uri.query()
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedPath == "/api/users"
    capturedQuery == "id=123&filter=active"
  }

  def "handles invalid base64 body gracefully"() {
    given:
    def eventJson = '''
    {
      "body": "not-valid-base64",
      "isBase64Encoded": true,
      "requestContext": {
        "httpMethod": "POST"
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedBody = "NOT_CALLED"

    setupMockCallbacks(
    onBody: { body ->
      capturedBody = body
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedBody == "NOT_CALLED"  // Should not call body callback when decode fails
  }

  def "handles base64 decoded empty string body"() {
    given:
    def base64Empty = Base64.getEncoder().encodeToString("".getBytes())
    def eventJson = """
    {
      "body": "${base64Empty}",
      "isBase64Encoded": true,
      "requestContext": {
        "httpMethod": "POST"
      }
    }
    """
    def event = createInputStream(eventJson)

    def capturedBody = "NOT_CALLED"

    setupMockCallbacks(
    onBody: { body ->
      capturedBody = body
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedBody == ""  // Should pass empty string after decoding
  }

  def "handles body with special characters"() {
    given:
    def eventJson = '''
    {
      "body": "{\\"text\\": \\"Hello 世界 🌍\\"}",
      "requestContext": {
        "httpMethod": "POST"
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedBody = null

    setupMockCallbacks(
    onBody: { body ->
      capturedBody = body
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedBody instanceof Map
    capturedBody.text == "Hello 世界 🌍"
  }

  // ============================================================================
  // Generic Data Extraction Tests
  // ============================================================================

  def "extracts data from unknown trigger type using generic extraction"() {
    given:
    def eventJson = '''
    {
      "path": "/generic/path",
      "httpMethod": "PATCH",
      "headers": {
        "x-custom-header": "generic-value"
      },
      "unknownField": "should be ignored",
      "requestContext": {
        "identity": {
          "sourceIp": "203.0.113.1"
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedMethod = null
    def capturedPath = null
    def capturedHeaders = [:]
    def capturedSourceIp = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedMethod = method
      capturedPath = uri.path()
    },
    onHeader: { name, value ->
      capturedHeaders[name] = value
    },
    onSocketAddress: { ip, port ->
      capturedSourceIp = ip
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedMethod == "PATCH"
    capturedPath == "/generic/path"
    capturedHeaders["x-custom-header"] == "generic-value"
    capturedSourceIp == "203.0.113.1"
  }

  def "extracts data from unknown trigger with http in requestContext"() {
    given:
    def eventJson = '''
    {
      "requestContext": {
        "http": {
          "method": "OPTIONS",
          "path": "/options/path",
          "sourceIp": "198.51.100.50"
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedMethod = null
    def capturedPath = null
    def capturedSourceIp = null

    setupMockCallbacks(
    onMethodUri: { method, uri ->
      capturedMethod = method
      capturedPath = uri.path()
    },
    onSocketAddress: { ip, port ->
      capturedSourceIp = ip
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedMethod == "OPTIONS"
    capturedPath == "/options/path"
    capturedSourceIp == "198.51.100.50"
  }

  def "handles cookies merging with existing cookie header"() {
    given:
    def eventJson = '''
    {
      "headers": {
        "cookie": "existing=value"
      },
      "cookies": ["new=cookie1", "another=cookie2"],
      "requestContext": {
        "http": {
          "method": "GET",
          "path": "/"
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedHeaders = [:]

    setupMockCallbacks(
    onHeader: { name, value ->
      capturedHeaders[name] = value
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    capturedHeaders["cookie"] == "existing=value; new=cookie1; another=cookie2"
  }

  def "handles empty cookies array correctly"() {
    given:
    def eventJson = '''
    {
      "headers": {
        "content-type": "application/json"
      },
      "cookies": [],
      "requestContext": {
        "http": {
          "method": "GET",
          "path": "/"
        }
      }
    }
    '''
    def event = createInputStream(eventJson)

    def capturedHeaders = [:]

    setupMockCallbacks(
    onHeader: { name, value ->
      capturedHeaders[name] = value
    }
    )

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null
    !capturedHeaders.containsKey("cookie")  // Empty array should not add cookie header
  }

  // ============================================================================
  // processRequestEnd Tests
  // ============================================================================

  def "processRequestEnd does nothing when span is null"() {
    when:
    LambdaAppSecHandler.processRequestEnd(null)

    then:
    noExceptionThrown()
  }

  def "processRequestEnd does nothing when AppSec is disabled"() {
    given:
    ActiveSubsystems.APPSEC_ACTIVE = false
    def span = Mock(AgentSpan)

    when:
    LambdaAppSecHandler.processRequestEnd(span)

    then:
    0 * span._
  }

  def "processRequestEnd does nothing when span has no RequestContext"() {
    given:
    def span = Mock(AgentSpan) {
      getRequestContext() >> null
    }

    when:
    LambdaAppSecHandler.processRequestEnd(span)

    then:
    noExceptionThrown()
  }

  def "processRequestEnd invokes requestEnded callback with RequestContext"() {
    given:
    def mockAppSecContext = new Object()
    def mockRequestContext = Mock(RequestContext) {
      getData(RequestContextSlot.APPSEC) >> mockAppSecContext
    }
    def span = Mock(AgentSpan) {
      getRequestContext() >> mockRequestContext
    }

    def callbackInvoked = false
    def capturedContext = null
    def capturedSpan = null

    def mockRequestEndedCallback = Mock(BiFunction) {
      apply(_ as RequestContext, _ as AgentSpan) >> {
        RequestContext ctx, AgentSpan s ->
        callbackInvoked = true
        capturedContext = ctx
        capturedSpan = s
        return new Flow.ResultFlow<>(null)
      }
    }

    def mockCallbackProvider = Mock(CallbackProvider) {
      getCallback(EVENTS.requestEnded()) >> mockRequestEndedCallback
    }

    def mockTracer = Mock(AgentTracer.TracerAPI) {
      getCallbackProvider(RequestContextSlot.APPSEC) >> mockCallbackProvider
    }

    AgentTracer.forceRegister(mockTracer)

    when:
    LambdaAppSecHandler.processRequestEnd(span)

    then:
    callbackInvoked
    capturedContext == mockRequestContext
    capturedSpan == span
  }

  def "processRequestEnd handles null requestEnded callback gracefully"() {
    given:
    def mockRequestContext = Mock(RequestContext)
    def span = Mock(AgentSpan) {
      getRequestContext() >> mockRequestContext
    }

    def mockCallbackProvider = Mock(CallbackProvider) {
      getCallback(EVENTS.requestEnded()) >> null
    }

    def mockTracer = Mock(AgentTracer.TracerAPI) {
      getCallbackProvider(RequestContextSlot.APPSEC) >> mockCallbackProvider
    }

    AgentTracer.forceRegister(mockTracer)

    when:
    LambdaAppSecHandler.processRequestEnd(span)

    then:
    noExceptionThrown()  // Should log warning but not throw
  }

  // ============================================================================
  // mergeContexts Tests
  // ============================================================================

  def "mergeContexts returns null when both contexts are null"() {
    when:
    def result = LambdaAppSecHandler.mergeContexts(null, null)

    then:
    result == null
  }

  def "mergeContexts returns extensionContext when appSecContext is null"() {
    given:
    def extensionContext = Mock(TagContext)

    when:
    def result = LambdaAppSecHandler.mergeContexts(extensionContext, null)

    then:
    result == extensionContext
  }

  def "mergeContexts returns appSecContext when extensionContext is null"() {
    given:
    def appSecContext = Mock(TagContext)

    when:
    def result = LambdaAppSecHandler.mergeContexts(null, appSecContext)

    then:
    result == appSecContext
  }

  def "mergeContexts merges AppSec data into TagContext"() {
    given:
    def appSecData = new Object()

    // Create real TagContext instances since methods are final
    def appSecContext = new TagContext()
    appSecContext.withRequestContextDataAppSec(appSecData)

    def extensionContext = new TagContext()

    when:
    def result = LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext)

    then:
    result == extensionContext
    result.getRequestContextDataAppSec() == appSecData
  }

  def "mergeContexts returns extensionContext when appSecContext is not TagContext"() {
    given:
    def extensionContext = Mock(TagContext)
    def appSecContext = Mock(AgentSpanContext)

    when:
    def result = LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext)

    then:
    result == extensionContext
  }

  def "mergeContexts returns extensionContext when it is not TagContext"() {
    given:
    def extensionContext = Mock(AgentSpanContext)
    def appSecContext = Mock(TagContext)

    when:
    def result = LambdaAppSecHandler.mergeContexts(extensionContext, appSecContext)

    then:
    result == extensionContext
  }

  // ============================================================================
  // Error Handling and Null Callback Tests
  // ============================================================================

  def "processRequestStart handles null requestStarted callback gracefully"() {
    given:
    def eventJson = '{"requestContext": {"httpMethod": "GET"}}'
    def event = createInputStream(eventJson)

    def mockCallbackProvider = Mock(CallbackProvider) {
      getCallback(EVENTS.requestStarted()) >> null
    }

    def mockTracer = Mock(AgentTracer.TracerAPI) {
      getCallbackProvider(RequestContextSlot.APPSEC) >> mockCallbackProvider
    }

    AgentTracer.forceRegister(mockTracer)

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result == null  // Should return null when requestStarted callback is missing
  }

  def "processRequestStart handles null methodUri callback gracefully"() {
    given:
    def eventJson = '''
    {
      "path": "/test",
      "requestContext": {
        "httpMethod": "GET"
      }
    }
    '''
    def event = createInputStream(eventJson)

    def mockAppSecContext = new Object()

    def mockRequestStartedCallback = Mock(Supplier) {
      get() >> new Flow.ResultFlow<>(mockAppSecContext)
    }

    def mockCallbackProvider = Mock(CallbackProvider) {
      getCallback(EVENTS.requestStarted()) >> mockRequestStartedCallback
      getCallback(EVENTS.requestMethodUriRaw()) >> null  // Null callback
      getCallback(EVENTS.requestHeader()) >> null
      getCallback(EVENTS.requestClientSocketAddress()) >> null
      getCallback(EVENTS.requestHeaderDone()) >> Mock(Function) {
        apply(_ as RequestContext) >> new Flow.ResultFlow<>(null)
      }
      getCallback(EVENTS.requestPathParams()) >> null
      getCallback(EVENTS.requestBodyProcessed()) >> null
    }

    def mockTracer = Mock(AgentTracer.TracerAPI) {
      getCallbackProvider(RequestContextSlot.APPSEC) >> mockCallbackProvider
    }

    AgentTracer.forceRegister(mockTracer)

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result != null  // Should continue processing even if methodUri callback is null
    result instanceof TagContext
  }

  def "processRequestStart handles exception during JSON parsing"() {
    given:
    def invalidJson = '{this is not valid JSON at all'
    def event = createInputStream(invalidJson)

    when:
    def result = LambdaAppSecHandler.processRequestStart(event)

    then:
    result == null  // Should return null on parse error
  }

  def "processRequestStart handles exception during stream reading"() {
    given:
    def mockStream = Mock(ByteArrayInputStream) {
      available() >> { throw new IOException("Stream error") }
    }

    when:
    def result = LambdaAppSecHandler.processRequestStart(mockStream)

    then:
    result == null  // Should return null on IO error
  }

  // ============================================================================
  // Helper Methods
  // ============================================================================

  private ByteArrayInputStream createInputStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
  }

  /**
   * Set up mock callbacks to capture invocations and verify data extraction.
   * This mocks the AgentTracer and callback provider to intercept gateway calls.
   */
  private void setupMockCallbacks(Map<String, Closure> callbacks) {
    def mockAppSecContext = new Object()

    def mockRequestStartedCallback = Mock(Supplier) {
      get() >> new Flow.ResultFlow<>(mockAppSecContext)
    }

    def mockMethodUriCallback = callbacks.onMethodUri ? Mock(datadog.trace.api.function.TriFunction) {
      apply(_ as RequestContext, _ as String, _ as URIDataAdapter) >> {
        RequestContext ctx, String method, URIDataAdapter uri ->
        callbacks.onMethodUri(method, uri)
        return new Flow.ResultFlow<>(null)
      }
    } : null

    def mockHeaderCallback = callbacks.onHeader ? Mock(TriConsumer) {
      accept(_ as RequestContext, _ as String, _ as String) >> {
        RequestContext ctx, String name, String value ->
        callbacks.onHeader(name, value)
      }
    } : null

    def mockSocketAddressCallback = callbacks.onSocketAddress ? Mock(TriFunction) {
      apply(_ as RequestContext, _ as String, _ as Integer) >> {
        RequestContext ctx, String ip, Integer port ->
        callbacks.onSocketAddress(ip, port)
        return new Flow.ResultFlow<>(null)
      }
    } : null

    def mockHeaderDoneCallback = Mock(Function) {
      apply(_ as RequestContext) >> new Flow.ResultFlow<>(null)
    }

    def mockPathParamsCallback = callbacks.onPathParams ? Mock(BiFunction) {
      apply(_ as RequestContext, _ as Map) >> {
        RequestContext ctx, Map params ->
        callbacks.onPathParams(params)
        return new Flow.ResultFlow<>(null)
      }
    } : null

    def mockQueryParamsCallback = callbacks.onQueryParams ? Mock(BiFunction) {
      apply(_ as RequestContext, _ as Map) >> {
        RequestContext ctx, Map params ->
        callbacks.onQueryParams(params)
        return new Flow.ResultFlow<>(null)
      }
    } : null

    def mockBodyCallback = callbacks.onBody ? Mock(BiFunction) {
      apply(_ as RequestContext, _ as Object) >> {
        RequestContext ctx, Object body ->
        callbacks.onBody(body)
        return new Flow.ResultFlow<>(null)
      }
    } : null

    def mockCallbackProvider = Mock(CallbackProvider) {
      getCallback(EVENTS.requestStarted()) >> mockRequestStartedCallback
      getCallback(EVENTS.requestMethodUriRaw()) >> mockMethodUriCallback
      getCallback(EVENTS.requestHeader()) >> mockHeaderCallback
      getCallback(EVENTS.requestClientSocketAddress()) >> mockSocketAddressCallback
      getCallback(EVENTS.requestHeaderDone()) >> mockHeaderDoneCallback
      getCallback(EVENTS.requestPathParams()) >> mockPathParamsCallback
      getCallback(EVENTS.requestBodyProcessed()) >> mockBodyCallback
    }

    def mockTracer = Mock(AgentTracer.TracerAPI) {
      getCallbackProvider(RequestContextSlot.APPSEC) >> mockCallbackProvider
    }

    // Install the mock tracer
    AgentTracer.forceRegister(mockTracer)
  }

  def cleanup() {
    // Reset tracer after each test
    AgentTracer.forceRegister(null)
  }
}
