import com.amazon.sqs.javamessaging.ProviderConfiguration
import com.amazon.sqs.javamessaging.SQSConnectionFactory
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.aws.ExpectedQueryParams
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import spock.lang.Shared

import javax.jms.Session

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

class LegacySqsClientForkedTest extends InstrumentationSpecification {

  def setup() {
    System.setProperty(SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "my-access-key")
    System.setProperty(SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "my-secret-key")
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("aws-sdk.legacy.tracing.enabled", "true")
    // Set a service name that gets sorted early with SORT_BY_NAMES
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A")
  }

  @Shared
  def credentialsProvider = AnonymousCredentialsProvider.create()
  @Shared
  def server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start()
  @Shared
  def address = server.waitUntilStarted().localAddress()
  @Shared
  def endpoint = URI.create("http://localhost:${address.port}")

  def cleanupSpec() {
    if (server != null) {
      try {
        server.stopAndWait()
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt()
      }
    }
  }

  def "trace details propagated via SQS system message attributes"() {
    setup:
    def client = SqsClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()
    def queueUrl = client.createQueue(CreateQueueRequest.builder().queueName('somequeue').build()).queueUrl()
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody('sometext').build())
    })
    def messages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build()).messages()

    then:
    def sendSpan
    assertTraces(2) {
      trace(3) {
        basicSpan(it, "parent")
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "Sqs.SendMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "SendMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
            urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams("SendMessage"))
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(1))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
      trace(2) {
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "Sqs.ReceiveMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "ReceiveMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
            urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams(("ReceiveMessage")))
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
      }
    }

    assert messages[0].attributesAsStrings()['AWSTraceHeader'] =~
    /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/

    cleanup:
    client.close()
  }

  def "trace details propagated from SQS to JMS"() {
    setup:
    def client = SqsClient.builder()
      .region(Region.EU_CENTRAL_1)
      .endpointOverride(endpoint)
      .credentialsProvider(credentialsProvider)
      .build()

    def connectionFactory = new SQSConnectionFactory(new ProviderConfiguration(), client)
    def connection = connectionFactory.createConnection()
    def session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    def queue = session.createQueue('somequeue')
    def consumer = session.createConsumer(queue)

    TEST_WRITER.clear()

    when:
    connection.start()
    TraceUtils.runUnderTrace('parent', {
      client.sendMessage(SendMessageRequest.builder().queueUrl(queue.queueUrl).messageBody('sometext').build())
    })
    def message = consumer.receive()
    consumer.receiveNoWait()

    then:
    def sendSpan
    assertTraces(4, SORT_TRACES_BY_NAMES) {
      trace(3) {
        basicSpan(it, "parent")
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "Sqs.SendMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "SendMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
            urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams("SendMessage"))
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(1))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
        sendSpan = span(1)
      }
      trace(1) {
        span {
          serviceName "jms"
          operationName "jms.consume"
          resourceName "Consumed from Queue somequeue"
          spanType DDSpanTypes.MESSAGE_CONSUMER
          errored false
          measured true
          childOf(sendSpan)
          tags {
            "$Tags.COMPONENT" "jms"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(2) {
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "Sqs.DeleteMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "DeleteMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
            urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams("DeleteMessage"))
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
      }
      trace(2) {
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "Sqs.ReceiveMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Sqs"
            "aws_service" "Sqs"
            "aws.operation" "ReceiveMessage"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            "aws.requestId" { it.trim() == "00000000-0000-0000-0000-000000000000" } // the test server seem messing with request id and insert \n
            urlTags("http://localhost:${address.port}/", ExpectedQueryParams.getExpectedQueryParams("ReceiveMessage"))
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
      }
    }

    def expectedTraceProperty = 'X-Amzn-Trace-Id'.toLowerCase(Locale.ENGLISH).replace('-', '__dash__')
    assert message.getStringProperty(expectedTraceProperty) =~
    /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/

    cleanup:
    session.close()
    connection.stop()
    client.close()
  }
}
