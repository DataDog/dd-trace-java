import com.amazon.sqs.javamessaging.ProviderConfiguration
import com.amazon.sqs.javamessaging.SQSConnectionFactory
import com.amazonaws.SDKGlobalConfiguration
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.GeneralConfig
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import spock.lang.Shared

import javax.jms.Session

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

class LegacySqsClientForkedTest extends InstrumentationSpecification {

  def setup() {
    System.setProperty(SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY, "my-access-key")
    System.setProperty(SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY, "my-secret-key")
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("aws-sdk.legacy.tracing.enabled", "true")

    // Set a service name that gets sorted early with SORT_BY_NAMES
    injectSysConfig(GeneralConfig.SERVICE_NAME, "A")
  }

  @Shared
  def credentialsProvider = new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())
  @Shared
  def server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start()
  @Shared
  def address = server.waitUntilStarted().localAddress()
  @Shared
  def endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost:${address.port}", "elasticmq")

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
    def client = AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
      .build()
    def queueUrl = client.createQueue('somequeue').queueUrl
    TEST_WRITER.clear()

    when:
    TraceUtils.runUnderTrace('parent', {
      client.sendMessage(queueUrl, 'sometext')
    })
    def messages = client.receiveMessage(queueUrl).messages

    then:
    def sendSpan
    assertTraces(2) {
      trace(3) {
        basicSpan(it, "parent")
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "SQS.SendMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "SendMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /?/somequeue"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(1))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/000000000000/somequeue"
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
          resourceName "SQS.ReceiveMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "ReceiveMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /?/somequeue"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/000000000000/somequeue"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
      }
    }

    assert messages[0].attributes['AWSTraceHeader'] =~
    /Root=1-[0-9a-f]{8}-00000000${sendSpan.traceId.toHexStringPadded(16)};Parent=${DDSpanId.toHexStringPadded(sendSpan.spanId)};Sampled=1/

    cleanup:
    client.shutdown()
  }

  def "trace details propagated from SQS to JMS"() {
    setup:
    def client = AmazonSQSClientBuilder.standard()
      .withEndpointConfiguration(endpoint)
      .withCredentials(credentialsProvider)
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
      client.sendMessage(queue.queueUrl, 'sometext')
    })
    def message = consumer.receive()
    consumer.receiveNoWait()

    then:
    def sendSpan
    // Order has changed in 1.10+ versions of amazon-sqs-java-messaging-lib
    // so sort by names service/operation/resource
    assertTraces(4, SORT_TRACES_BY_NAMES) {
      trace(3) {
        basicSpan(it, "parent")
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "SQS.SendMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "SendMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /?/somequeue"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(1))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/000000000000/somequeue"
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
            // This shows up in 1.10+ versions of amazon-sqs-java-messaging-lib
            if (isLatestDepTest) {
              "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            }
            defaultTags(true)
          }
        }
      }
      trace(2) {
        span {
          serviceName "sqs"
          operationName "aws.http"
          resourceName "SQS.DeleteMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "DeleteMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /?/somequeue"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/000000000000/somequeue"
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
          resourceName "SQS.ReceiveMessage"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${address.port}/"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "AmazonSQS"
            "aws_service" "sqs"
            "aws.endpoint" "http://localhost:${address.port}"
            "aws.operation" "ReceiveMessageRequest"
            "aws.agent" "java-aws-sdk"
            "aws.queue.url" "http://localhost:${address.port}/000000000000/somequeue"
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "POST /?/somequeue"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" address.port
            "$Tags.HTTP_URL" "http://localhost:${address.port}/000000000000/somequeue"
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
    client.shutdown()
  }
}
