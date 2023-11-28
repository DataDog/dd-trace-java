package datadog.trace.lambda

import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.core.DDSpan
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.SNSEvent
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class LambdaHandlerTest extends DDCoreSpecification {

  class TestObject {

    public String field1
    public boolean field2

    TestObject() {
      this.field1 = "toto"
      this.field2 = true
    }
  }

  def "test start invocation success"() {
    given:
    Config config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512

    def server = httpServer {
      handlers {
        post("/lambda/start-invocation") {
          response
            .status(200)
            .addHeader("x-datadog-trace-id", "1234")
            .addHeader("x-datadog-sampling-priority", "2")
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())

    when:
    def objTest = LambdaHandler.notifyStartInvocation(obj, PropagationTags.factory(config))

    then:
    objTest.getTraceId().toString() == traceId
    objTest.getSamplingPriority() == samplingPriority

    cleanup:
    server.close()

    where:
    traceId    | samplingPriority      | obj
    "1234"     | 2                     | new TestObject()
  }

  def "test start invocation failure"() {
    given:
    Config config = Mock(Config)
    config.getxDatadogTagsMaxLength() >> 512

    def server = httpServer {
      handlers {
        post("/lambda/start-invocation") {
          response
            .status(500)
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())

    when:
    def objTest = LambdaHandler.notifyStartInvocation(obj, PropagationTags.factory(config))

    then:
    objTest == expected

    cleanup:
    server.close()

    where:
    expected    | obj
    null        | new TestObject()
  }

  def "test end invocation success"() {
    given:
    def server = httpServer {
      handlers {
        post("/lambda/end-invocation") {
          response
            .status(200)
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDTraceId.from("1234")
      getSpanId() >> DDSpanId.from("5678")
      getSamplingPriority() >> 2
    }

    when:
    def result = LambdaHandler.notifyEndInvocation(span, lambdaResult, boolValue)

    then:
    server.lastRequest.headers.get("x-datadog-invocation-error") == eHeaderValue
    server.lastRequest.headers.get("x-datadog-trace-id") == tIdHeaderValue
    server.lastRequest.headers.get("x-datadog-span-id") == sIdHeaderValue
    server.lastRequest.headers.get("x-datadog-sampling-priority") == sPIdHeaderValue
    result == expected

    cleanup:
    server.close()

    where:
    expected | eHeaderValue | tIdHeaderValue | sIdHeaderValue | sPIdHeaderValue | lambdaResult | boolValue
    true     | "true"       | "1234"         | "5678"         | "2"             | {}           | true
    true     | null         | "1234"         | "5678"         | "2"             | "12345"      | false
  }

  def "test end invocation failure"() {
    given:
    def server = httpServer {
      handlers {
        post("/lambda/end-invocation") {
          response
            .status(500)
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDTraceId.from("1234")
      getSpanId() >> DDSpanId.from("5678")
      getSamplingPriority() >> 2
    }

    when:
    def result = LambdaHandler.notifyEndInvocation(span, lambdaResult, boolValue)

    then:
    result == expected
    server.lastRequest.headers.get("x-datadog-invocation-error") == headerValue

    cleanup:
    server.close()

    where:
    expected | headerValue | lambdaResult | boolValue
    false    | "true"      | {}           | true
    false    | null        | "12345"      | false
  }

  def "test end invocation success with error metadata"() {
    given:
    def server = httpServer {
      handlers {
        post("/lambda/end-invocation") {
          response
            .status(200)
            .send()
        }
      }
    }
    LambdaHandler.setExtensionBaseUrl(server.address.toString())
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDTraceId.from("1234")
      getSpanId() >> DDSpanId.from("5678")
      getSamplingPriority() >> 2
      getTag(DDTags.ERROR_MSG) >> "custom error message"
      getTag(DDTags.ERROR_TYPE) >> "java.lang.Throwable"
      getTag(DDTags.ERROR_STACK) >> "errorStack\n \ttest"
    }

    when:
    LambdaHandler.notifyEndInvocation(span, {}, true)

    then:
    server.lastRequest.headers.get("x-datadog-invocation-error") == "true"
    server.lastRequest.headers.get("x-datadog-invocation-error-msg") == "custom error message"
    server.lastRequest.headers.get("x-datadog-invocation-error-type") == "java.lang.Throwable"
    server.lastRequest.headers.get("x-datadog-invocation-error-stack") == "ZXJyb3JTdGFjawogCXRlc3Q="

    cleanup:
    server.close()
  }

  def "test moshi toJson SQSEvent"() {
    given:
    def myEvent = new SQSEvent()
    List<SQSEvent.SQSMessage> records = new ArrayList<>()
    SQSEvent.SQSMessage message = new SQSEvent.SQSMessage()
    message.setMessageId("myId")
    message.setAwsRegion("myRegion")
    records.add(message)
    myEvent.setRecords(records)

    when:
    def result = LambdaHandler.writeValueAsString(myEvent)

    then:
    result == "{\"records\":[{\"awsRegion\":\"myRegion\",\"messageId\":\"myId\"}]}"
  }

  def "test moshi toJson S3Event"() {
    given:
    List<S3EventNotification.S3EventNotificationRecord> list = new ArrayList<>()
    S3EventNotification.S3EventNotificationRecord item0 = new S3EventNotification.S3EventNotificationRecord(
      "region", "eventName", "mySource", null, "3.4",
      null, null, null, null)
    list.add(item0)
    def myEvent = new S3Event(list)

    when:
    def result = LambdaHandler.writeValueAsString(myEvent)

    then:
    result == "{\"records\":[{\"awsRegion\":\"region\",\"eventName\":\"eventName\",\"eventSource\":\"mySource\",\"eventVersion\":\"3.4\"}]}"
  }

  def "test moshi toJson SNSEvent"() {
    given:
    def myEvent = new SNSEvent()
    List<SNSEvent.SNSRecord> records = new ArrayList<>()
    SNSEvent.SNSRecord message = new SNSEvent.SNSRecord()
    message.setEventSource("mySource")
    message.setEventVersion("myVersion")
    records.add(message)
    myEvent.setRecords(records)

    when:
    def result = LambdaHandler.writeValueAsString(myEvent)

    then:
    result == "{\"records\":[{\"eventSource\":\"mySource\",\"eventVersion\":\"myVersion\"}]}"
  }

  def "test moshi toJson APIGatewayProxyRequestEvent"() {
    given:
    def myEvent = new APIGatewayProxyRequestEvent()
    myEvent.setBody("bababango")
    myEvent.setHttpMethod("POST")

    when:
    def result = LambdaHandler.writeValueAsString(myEvent)

    then:
    result == "{\"body\":\"bababango\",\"httpMethod\":\"POST\"}"
  }

  def "test moshi toJson InputStream"() {
    given:
    def body = "{\"body\":\"bababango\",\"httpMethod\":\"POST\"}"
    def myEvent = new ByteArrayInputStream(body.getBytes())

    when:
    def result = LambdaHandler.writeValueAsString(myEvent)

    then:
    result == body
  }
}
