import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonWebServiceClient
import com.amazonaws.ClientConfiguration
import com.amazonaws.Request
import com.amazonaws.SDKGlobalConfiguration
import com.amazonaws.SdkClientException
import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.AnonymousAWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.auth.InstanceProfileCredentialsProvider
import com.amazonaws.auth.SystemPropertiesCredentialsProvider
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.handlers.RequestHandler2
import com.amazonaws.regions.Regions
import com.amazonaws.retry.PredefinedRetryPolicies
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest
import com.amazonaws.services.dynamodbv2.model.GetItemRequest
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.DeleteStreamRequest
import com.amazonaws.services.rds.AmazonRDSClientBuilder
import com.amazonaws.services.rds.model.DeleteOptionGroupRequest
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.sns.AmazonSNSClientBuilder
import com.amazonaws.services.sns.model.PublishRequest
import com.amazonaws.services.sqs.AmazonSQSClientBuilder
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.SendMessageRequest
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.http.conn.HttpHostConnectException
import org.apache.http.impl.execchain.RequestAbortedException
import org.json.XML
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class LegacyAWS1ClientForkedTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("aws-sdk.legacy.tracing.enabled", "true")
  }

  private static final CREDENTIALS_PROVIDER_CHAIN = new AWSCredentialsProviderChain(
  new EnvironmentVariableCredentialsProvider(),
  new SystemPropertiesCredentialsProvider(),
  new ProfileCredentialsProvider(),
  new InstanceProfileCredentialsProvider())

  def setup() {
    System.setProperty(SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY, "my-access-key")
    System.setProperty(SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY, "my-secret-key")
  }

  @Shared
  def credentialsProvider = new AWSStaticCredentialsProvider(new AnonymousAWSCredentials())
  @Shared
  def responseBody = new AtomicReference<String>()
  @Shared
  def jsonPointer = new AtomicReference<String>()

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      all {
        def body = responseBody.get()
        if (request.headers.get("Content-Type")?.contains("json")) {
          def json = XML.toJSONObject(body)
          if (jsonPointer.get() != null) {
            json = json.query(jsonPointer.get())
          }
          body = json.toString()
        }
        response.status(200).send(body)
      }
    }
  }
  @Shared
  def endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost:$server.address.port", "us-west-2")

  def "request handler is hooked up with builder"() {
    setup:
    def builder = AmazonS3ClientBuilder.standard()
      .withRegion(Regions.US_EAST_1)
    if (addHandler) {
      builder.withRequestHandlers(new RequestHandler2() {})
    }
    AmazonWebServiceClient client = builder.build()

    expect:
    client.requestHandler2s != null
    client.requestHandler2s.size() == size
    client.requestHandler2s.get(position).getClass().getSimpleName() == "TracingRequestHandler"

    where:
    addHandler | size | position
    true       | 2    | 1
    false      | 1    | 0
  }

  def "request handler is hooked up with constructor"() {
    setup:
    String accessKey = "asdf"
    String secretKey = "qwerty"
    def credentials = new BasicAWSCredentials(accessKey, secretKey)
    def client = new AmazonS3Client(credentials)
    if (addHandler) {
      client.addRequestHandler(new RequestHandler2() {})
    }

    expect:
    client.requestHandler2s != null
    client.requestHandler2s.size() == size
    client.requestHandler2s.get(0).getClass().getSimpleName() == "TracingRequestHandler"

    where:
    addHandler | size
    true       | 2
    false      | 1
  }

  def "send #operation request with mocked response"() {
    setup:
    responseBody.set(body)
    jsonPointer.set(jsonPointerStr)

    when:
    def response = call.call(client)

    then:
    response != null

    client.requestHandler2s != null
    // check we instrumented with exactly one TracingRequestHandler:
    client.requestHandler2s.findAll{ it.getClass().getSimpleName() == "TracingRequestHandler" }.size() == 1

    assertTraces(1) {
      trace(2) {
        span {
          serviceName "$ddService"
          operationName "aws.http"
          resourceName "$service.$operation"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "$server.address/"
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "$Tags.PEER_PORT" server.address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" { it.contains(service) }
            "aws_service" { it.contains(service.toLowerCase()) }
            "aws.endpoint" "$server.address"
            "aws.operation" "${operation}Request"
            "aws.agent" "java-aws-sdk"
            for (def addedTag : additionalTags) {
              "$addedTag.key" "$addedTag.value"
            }
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "$method $path"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "${server.address}${path}"
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            defaultTags()
          }
        }
      }
    }
    server.lastRequest.headers.get("x-datadog-trace-id") == null
    server.lastRequest.headers.get("x-datadog-parent-id") == null

    cleanup:
    jsonPointer.set(null)

    where:
    service      | operation           | ddService      | method | path                  | client                                                                                                                                             | call                                                                            | additionalTags                    | body                            | jsonPointerStr
    "S3"         | "CreateBucket"      | "java-aws-sdk" | "PUT"  | "/testbucket/"        | AmazonS3ClientBuilder.standard().withPathStyleAccessEnabled(true).withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build() | { c -> c.createBucket("testbucket") }                                           | ["aws.bucket.name": "testbucket", "bucketname": "testbucket"] | ""  | null
    "S3"         | "GetObject"         | "java-aws-sdk" | "GET"  | "/someBucket/someKey" | AmazonS3ClientBuilder.standard().withPathStyleAccessEnabled(true).withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build() | { c -> c.getObject("someBucket", "someKey") }                                   | ["aws.bucket.name": "someBucket", "bucketname": "someBucket", "aws.object.key": "someKey"] | ""  | null
    "DynamoDBv2" | "CreateTable"       | "java-aws-sdk" | "POST" | "/"                   | AmazonDynamoDBClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                            | { c -> c.createTable(new CreateTableRequest("sometable", null)) }               | ["aws.table.name": "sometable", "tablename": "sometable"]   | ""    | null
    "DynamoDBv2" | "GetItem"           | "java-aws-sdk" | "POST" | "/"                   | AmazonDynamoDBClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                            | { c -> c.getItem(new GetItemRequest("sometable", ["attribute": new AttributeValue("somevalue")])) } | ["aws.table.name": "sometable", "tablename": "sometable"] | "" | null
    "Kinesis"    | "DeleteStream"      | "java-aws-sdk" | "POST" | "/"                   | AmazonKinesisClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                             | { c -> c.deleteStream(new DeleteStreamRequest().withStreamName("somestream")) } | ["aws.stream.name": "somestream", "streamname": "somestream"] | ""  | null
    "SQS"        | "CreateQueue"       | "java-aws-sdk" | "POST" | "/"                   | AmazonSQSClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                                 | { c -> c.createQueue(new CreateQueueRequest("somequeue")) }                     | ["aws.queue.name": "somequeue", "queuename": "somequeue"]   | """
        <CreateQueueResponse>
            <CreateQueueResult><QueueUrl>https://queue.amazonaws.com/123456789012/MyQueue</QueueUrl></CreateQueueResult>
            <ResponseMetadata><RequestId>7a62c49f-347e-4fc4-9331-6e8e7a96aa73</RequestId></ResponseMetadata>
        </CreateQueueResponse>
      """ | "/CreateQueueResponse/CreateQueueResult"
    "SQS"        | "SendMessage"       | "sqs"          | "POST" | "/someurl"            | AmazonSQSClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                                 | { c -> c.sendMessage(new SendMessageRequest("someurl", "")) }                   | ["aws.queue.url": "someurl"]      | """
        <SendMessageResponse>
            <SendMessageResult>
                <MD5OfMessageBody>d41d8cd98f00b204e9800998ecf8427e</MD5OfMessageBody>
                <MD5OfMessageAttributes>3ae8f24a165a8cedc005670c81a27295</MD5OfMessageAttributes>
                <MessageId>5fea7756-0ea4-451a-a703-a558b933e274</MessageId>
            </SendMessageResult>
            <ResponseMetadata><RequestId>27daac76-34dd-47df-bd01-1f6e873584a0</RequestId></ResponseMetadata>
        </SendMessageResponse>
      """ | "/SendMessageResponse/SendMessageResult"
    "SNS"        | "Publish"           | "sns"          | "POST" | "/"                   | AmazonSNSClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                                 | { c -> c.publish(new PublishRequest("arn:aws:sns::123:some-topic", "")) }       | ["aws.topic.name": "some-topic", "topicname": "some-topic"]  | """
        <PublishResponse xmlns="https://sns.amazonaws.com/doc/2010-03-31/">
            <PublishResult>
                <MessageId>567910cd-659e-55d4-8ccb-5aaf14679dc0</MessageId>
            </PublishResult>
            <ResponseMetadata><RequestId>d74b8436-ae13-5ab4-a9ff-ce54dfea72a0</RequestId></ResponseMetadata>
        </PublishResponse>
      """ | "/PublishResponse/PublishResult"
    "EC2"        | "AllocateAddress"   | "java-aws-sdk" | "POST" | "/"                   | AmazonEC2ClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                                 | { c -> c.allocateAddress() }                                                    | [:]                               | """
        <AllocateAddressResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
           <requestId>59dbff89-35bd-4eac-99ed-be587EXAMPLE</requestId>
           <publicIp>192.0.2.1</publicIp>
           <domain>standard</domain>
        </AllocateAddressResponse>
      """ | null
    "RDS"        | "DeleteOptionGroup" | "java-aws-sdk" | "POST" | "/"                   | AmazonRDSClientBuilder.standard().withEndpointConfiguration(endpoint).withCredentials(credentialsProvider).build()                                 | { c -> c.deleteOptionGroup(new DeleteOptionGroupRequest()) }                    | [:]                               | """
        <DeleteOptionGroupResponse xmlns="http://rds.amazonaws.com/doc/2014-09-01/">
          <ResponseMetadata>
            <RequestId>0ac9cda2-bbf4-11d3-f92b-31fa5e8dbc99</RequestId>
          </ResponseMetadata>
        </DeleteOptionGroupResponse>
      """ | null
  }

  def "send #operation request to closed port"() {
    setup:
    responseBody.set(body)

    when:
    call.call(client)

    then:
    thrown SdkClientException

    assertTraces(1) {
      trace(2) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "$service.$operation"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "http://localhost:${UNUSABLE_PORT}/"
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" 61
            "aws.service" { it.contains(service) }
            "aws_service" { it.contains(service.toLowerCase()) }
            "aws.endpoint" "http://localhost:${UNUSABLE_PORT}"
            "aws.operation" "${operation}Request"
            "aws.agent" "java-aws-sdk"
            for (def addedTag : additionalTags) {
              "$addedTag.key" "$addedTag.value"
            }
            errorTags SdkClientException, ~/Unable to execute HTTP request/
            defaultTags()
          }
        }
        span {
          operationName "http.request"
          resourceName "$method /$url"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          childOf(span(0))
          tags {
            "$Tags.COMPONENT" "apache-httpclient"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" UNUSABLE_PORT
            "$Tags.HTTP_URL" "http://localhost:${UNUSABLE_PORT}/$url"
            "$Tags.HTTP_METHOD" "$method"
            errorTags HttpHostConnectException, ~/Connection refused/
            defaultTags()
          }
        }
      }
    }

    where:
    service | operation   | method | url                  | call                                                    | additionalTags                    | body | client
    "S3" | "GetObject" | "GET" | "someBucket/someKey" | { c -> c.getObject("someBucket", "someKey") } | ["aws.bucket.name": "someBucket", "bucketname": "someBucket", "aws.object.key": "someKey"] | "" | new AmazonS3Client(CREDENTIALS_PROVIDER_CHAIN, new ClientConfiguration().withRetryPolicy(PredefinedRetryPolicies.getDefaultRetryPolicyWithCustomMaxRetries(0))).withEndpoint("http://localhost:${UNUSABLE_PORT}")
  }

  def "naughty request handler doesn't break the trace"() {
    setup:
    def client = new AmazonS3Client(CREDENTIALS_PROVIDER_CHAIN)
    client.addRequestHandler(new RequestHandler2() {
        void beforeRequest(Request<?> request) {
          throw new RuntimeException("bad handler")
        }
      })

    when:
    client.getObject("someBucket", "someKey")

    then:
    activeSpan() == null
    thrown RuntimeException

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.HeadBucket"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "https://s3.amazonaws.com/"
            "$Tags.HTTP_METHOD" "HEAD"
            "$Tags.PEER_HOSTNAME" "s3.amazonaws.com"
            "aws.service" "Amazon S3"
            "aws_service" "s3"
            "aws.endpoint" "https://s3.amazonaws.com"
            "aws.operation" "HeadBucketRequest"
            "aws.agent" "java-aws-sdk"
            "aws.bucket.name" "someBucket"
            "bucketname" "someBucket"
            errorTags RuntimeException, "bad handler"
            defaultTags()
          }
        }
      }
    }
  }

  def "timeout and retry errors captured"() {
    setup:
    def server = httpServer {
      handlers {
        all {
          Thread.sleep(500)
          response.status(200).send()
        }
      }
    }
    AmazonS3Client client = new AmazonS3Client(new ClientConfiguration().withRequestTimeout(50 /* ms */))
      .withEndpoint("http://localhost:$server.address.port")

    when:
    client.getObject("someBucket", "someKey")

    then:
    activeSpan() == null
    thrown AmazonClientException

    assertTraces(1) {
      trace(5) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.GetObject"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "$server.address/"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" "Amazon S3"
            "aws_service" "s3"
            "aws.endpoint" "$server.address"
            "aws.operation" "GetObjectRequest"
            "aws.agent" "java-aws-sdk"
            "aws.bucket.name" "someBucket"
            "bucketname" "someBucket"
            "aws.object.key" "someKey"
            try {
              errorTags AmazonClientException, ~/Unable to execute HTTP request/
            } catch (AssertionError e) {
              errorTags SdkClientException, ~/Unable to execute HTTP request.*(?:interrupted|timeout).*/
            }
            defaultTags()
          }
        }
        (1..4).each {
          span {
            operationName "http.request"
            resourceName "GET /someBucket/someKey"
            spanType DDSpanTypes.HTTP_CLIENT
            errored true
            measured true
            childOf(span(0))
            tags {
              "$Tags.COMPONENT" "apache-httpclient"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.PEER_HOSTNAME" "localhost"
              "$Tags.PEER_PORT" server.address.port
              "$Tags.HTTP_URL" "$server.address/someBucket/someKey"
              "$Tags.HTTP_METHOD" "GET"
              try {
                errorTags SocketException, "Socket closed"
              } catch (AssertionError e) {
                try {
                  errorTags RequestAbortedException, "Request aborted"
                } catch (AssertionError e2) {
                  errorTags InterruptedIOException, ~/.*(?:interrupted|timeout).*/
                }
              }
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    server.close()
  }
}
