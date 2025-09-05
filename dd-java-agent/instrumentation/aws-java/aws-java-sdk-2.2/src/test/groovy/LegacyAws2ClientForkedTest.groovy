import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.aws.ExpectedQueryParams
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import software.amazon.awssdk.services.ec2.Ec2AsyncClient
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest
import software.amazon.awssdk.services.rds.RdsAsyncClient
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.rds.model.DeleteOptionGroupRequest
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.StorageClass
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.time.Duration
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class LegacyAws2ClientForkedTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("aws-sdk.legacy.tracing.enabled", "true")
  }

  private static final StaticCredentialsProvider CREDENTIALS_PROVIDER = StaticCredentialsProvider
  .create(AwsBasicCredentials.create("my-access-key", "my-secret-key"))

  @Shared
  def responseBody = new AtomicReference<String>()

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      all {
        response.status(200).send(responseBody.get())
      }
    }
  }

  def watch(builder, callback) {
    builder.addExecutionInterceptor(new ExecutionInterceptor() {
        @Override
        void afterExecution(Context.AfterExecution context, ExecutionAttributes executionAttributes) {
          callback.call()
        }
      })
  }

  def "send #operation request with builder {#builder.class.getName()} mocked response"() {
    setup:
    boolean executed = false
    def client = builder
      // tests that our instrumentation doesn't disturb any overridden configuration
      .overrideConfiguration({ watch(it, { executed = true }) })
      .endpointOverride(server.address)
      .region(Region.AP_NORTHEAST_1)
      .credentialsProvider(CREDENTIALS_PROVIDER)
      .build()
    responseBody.set(body)
    when:
    def response = call.call(client)

    if (response instanceof Future) {
      response = response.get()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    executed
    response != null
    response.class.simpleName.startsWith(operation) || response instanceof ResponseInputStream

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
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "aws.service" "$service"
            "aws_service" "$service"
            "aws.operation" "${operation}"
            "aws.agent" "java-aws-sdk"
            "aws.requestId" "$requestId"
            if (service == "S3") {
              "aws.bucket.name" "somebucket"
              "bucketname" "somebucket"
              if (operation == "PutObject") {
                "aws.storage.class" "GLACIER"
              }
              if (operation == "PutObject" || operation == "GetObject") {
                "aws.object.key" "somekey"
              }
            } else if (service == "Sqs" && operation == "CreateQueue") {
              "aws.queue.name" "somequeue"
              "queuename" "somequeue"
            } else if (service == "Sqs" && operation == "SendMessage") {
              "aws.queue.url" "someurl"
            } else if (service == "Sns" && operation == "Publish") {
              "aws.topic.name" "some-topic"
              "topicname" "some-topic"
            } else if (service == "DynamoDb") {
              "aws.table.name" "sometable"
              "tablename" "sometable"
            } else if (service == "Kinesis") {
              "aws.stream.name" "somestream"
              "streamname" "somestream"
            }
            urlTags("${server.address}${path}", ExpectedQueryParams.getExpectedQueryParams(operation))
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

    where:
    service    | operation           | ddService      | method | path                  | requestId                              | builder                  | call                                                                                                                                                            | body
    "S3"       | "CreateBucket"      | "java-aws-sdk" | "PUT"  | "/somebucket"         | "UNKNOWN"                              | S3Client.builder()       | { c -> c.createBucket(CreateBucketRequest.builder().bucket("somebucket").build()) }                                                                             | ""
    "S3"       | "GetObject"         | "java-aws-sdk" | "GET"  | "/somebucket/somekey" | "UNKNOWN"                              | S3Client.builder()       | { c -> c.getObject(GetObjectRequest.builder().bucket("somebucket").key("somekey").build()) }                                                                    | ""
    "S3"       | "PutObject"         | "java-aws-sdk" | "PUT"  | "/somebucket/somekey" | "UNKNOWN"                              | S3Client.builder()       | { c -> c.putObject(PutObjectRequest.builder().bucket("somebucket").key("somekey").storageClass(StorageClass.GLACIER).build(), RequestBody.fromString("body")) } | "body"
    "DynamoDb" | "CreateTable"       | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | DynamoDbClient.builder() | { c -> c.createTable(CreateTableRequest.builder().tableName("sometable").build()) }                                                                             | ""
    "DynamoDb" | "GetItem"           | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | DynamoDbClient.builder() | { c -> c.getItem(GetItemRequest.builder().tableName("sometable").key(["attribute": AttributeValue.builder().s("somevalue").build()]).build()) }                 | ""
    "DynamoDb" | "UpdateItem"        | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | DynamoDbClient.builder() | { c -> c.updateItem(UpdateItemRequest.builder().tableName("sometable").key(["attribute": AttributeValue.builder().s("somevalue").build()]).build()) }           | ""
    "Kinesis"  | "DeleteStream"      | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | KinesisClient.builder()  | { c -> c.deleteStream(DeleteStreamRequest.builder().streamName("somestream").build()) }                                                                         | ""
    "Sqs"      | "CreateQueue"       | "java-aws-sdk" | "POST" | "/"                   | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | SqsClient.builder()      | { c -> c.createQueue(CreateQueueRequest.builder().queueName("somequeue").build()) }                                                                             | """
        <CreateQueueResponse>
            <CreateQueueResult><QueueUrl>https://queue.amazonaws.com/123456789012/MyQueue</QueueUrl></CreateQueueResult>
            <ResponseMetadata><RequestId>7a62c49f-347e-4fc4-9331-6e8e7a96aa73</RequestId></ResponseMetadata>
        </CreateQueueResponse>
        """
    "Sqs"      | "SendMessage"       | "sqs"          | "POST" | "/"                   | "27daac76-34dd-47df-bd01-1f6e873584a0" | SqsClient.builder()      | { c -> c.sendMessage(SendMessageRequest.builder().queueUrl("someurl").messageBody("").build()) }                                                                | """
        <SendMessageResponse>
            <SendMessageResult>
                <MD5OfMessageBody>d41d8cd98f00b204e9800998ecf8427e</MD5OfMessageBody>
                <MD5OfMessageAttributes>3ae8f24a165a8cedc005670c81a27295</MD5OfMessageAttributes>
                <MessageId>5fea7756-0ea4-451a-a703-a558b933e274</MessageId>
            </SendMessageResult>
            <ResponseMetadata><RequestId>27daac76-34dd-47df-bd01-1f6e873584a0</RequestId></ResponseMetadata>
        </SendMessageResponse>
        """
    "Sns"      | "Publish"           | "sns"          | "POST" | "/"                   | "d74b8436-ae13-5ab4-a9ff-ce54dfea72a0" | SnsClient.builder()      | { c -> c.publish(PublishRequest.builder().topicArn("arn:aws:sns::123:some-topic").message("").build()) }                                                        | """
        <PublishResponse xmlns="https://sns.amazonaws.com/doc/2010-03-31/">
            <PublishResult>
                <MessageId>567910cd-659e-55d4-8ccb-5aaf14679dc0</MessageId>
            </PublishResult>
            <ResponseMetadata><RequestId>d74b8436-ae13-5ab4-a9ff-ce54dfea72a0</RequestId></ResponseMetadata>
        </PublishResponse>
        """
    "Ec2"      | "AllocateAddress"   | "java-aws-sdk" | "POST" | "/"                   | "59dbff89-35bd-4eac-99ed-be587EXAMPLE" | Ec2Client.builder()      | { c -> c.allocateAddress() }                                                                                                                                    | """
        <AllocateAddressResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
           <requestId>59dbff89-35bd-4eac-99ed-be587EXAMPLE</requestId> 
           <publicIp>192.0.2.1</publicIp>
           <domain>standard</domain>
        </AllocateAddressResponse>
        """
    "Rds"      | "DeleteOptionGroup" | "java-aws-sdk" | "POST" | "/"                   | "0ac9cda2-bbf4-11d3-f92b-31fa5e8dbc99" | RdsClient.builder()      | { c -> c.deleteOptionGroup(DeleteOptionGroupRequest.builder().build()) }                                                                                        | """
        <DeleteOptionGroupResponse xmlns="http://rds.amazonaws.com/doc/2014-09-01/">
          <ResponseMetadata><RequestId>0ac9cda2-bbf4-11d3-f92b-31fa5e8dbc99</RequestId></ResponseMetadata>
        </DeleteOptionGroupResponse>
        """
  }

  def "send #operation async request with builder {#builder.class.getName()} mocked response"() {
    setup:
    boolean executed = false
    def client = builder
      // tests that our instrumentation doesn't disturb any overridden configuration
      .overrideConfiguration({ watch(it, { executed = true }) })
      .endpointOverride(server.address)
      .region(Region.AP_NORTHEAST_1)
      .credentialsProvider(CREDENTIALS_PROVIDER)
      .build()
    responseBody.set(body)
    when:
    def response = call.call(client)

    if (response instanceof Future) {
      response = response.get()
    }
    TEST_WRITER.waitForTraces(1)

    then:
    executed
    response != null

    assertTraces(2) {
      trace(1) {
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
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_METHOD" "$method"
            "$Tags.HTTP_STATUS" 200
            "aws.service" "$service"
            "aws_service" "$service"
            "aws.operation" "${operation}"
            "aws.agent" "java-aws-sdk"
            "aws.requestId" "$requestId"
            if (service == "S3") {
              "aws.bucket.name" "somebucket"
              "bucketname" "somebucket"
              if (operation == "PutObject" || operation == "GetObject") {
                "aws.object.key" "somekey"
              }
            } else if (service == "Sqs" && operation == "CreateQueue") {
              "aws.queue.name" "somequeue"
              "queuename" "somequeue"
            } else if (service == "Sqs" && operation == "SendMessage") {
              "aws.queue.url" "someurl"
            } else if (service == "Sns" && operation == "Publish") {
              "aws.topic.name" "some-topic"
              "topicname" "some-topic"
            } else if (service == "DynamoDb") {
              "aws.table.name" "sometable"
              "tablename" "sometable"
            } else if (service == "Kinesis") {
              "aws.stream.name" "somestream"
              "streamname" "somestream"
            }
            urlTags("${server.address}${path}", ExpectedQueryParams.getExpectedQueryParams(operation))
            defaultTags()
          }
        }
      }
      // TODO: this should be part of the same trace but netty instrumentation doesn't cooperate
      trace(1) {
        span {
          operationName "netty.client.request"
          resourceName "$method $path"
          spanType DDSpanTypes.HTTP_CLIENT
          errored false
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "netty-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
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

    where:
    service    | operation           | ddService      | method | path                  | requestId                              | builder                       | call                                                                                                                             | body
    "S3"       | "CreateBucket"      | "java-aws-sdk" | "PUT"  | "/somebucket"         | "UNKNOWN"                              | S3AsyncClient.builder()       | { c -> c.createBucket(CreateBucketRequest.builder().bucket("somebucket").build()) }                                              | ""
    "S3"       | "GetObject"         | "java-aws-sdk" | "GET"  | "/somebucket/somekey" | "UNKNOWN"                              | S3AsyncClient.builder()       | { c -> c.getObject(GetObjectRequest.builder().bucket("somebucket").key("somekey").build(), AsyncResponseTransformer.toBytes()) } | "1234567890"
    "DynamoDb" | "CreateTable"       | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | DynamoDbAsyncClient.builder() | { c -> c.createTable(CreateTableRequest.builder().tableName("sometable").build()) }                                              | ""
    "DynamoDb" | "GetItem"           | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | DynamoDbAsyncClient.builder() | { c -> c.getItem(GetItemRequest.builder().tableName("sometable").key(["attribute": AttributeValue.builder().s("somevalue").build()]).build()) }       | ""
    "DynamoDb" | "UpdateItem"        | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | DynamoDbAsyncClient.builder() | { c -> c.updateItem(UpdateItemRequest.builder().tableName("sometable").key(["attribute": AttributeValue.builder().s("somevalue").build()]).build()) } | ""
    // Kinesis seems to expect an http2 response which is incompatible with our test server.
    // "Kinesis"  | "DeleteStream"      | "java-aws-sdk" | "POST" | "/"                   | "UNKNOWN"                              | KinesisAsyncClient.builder()  | { c -> c.deleteStream(DeleteStreamRequest.builder().streamName("somestream").build()) }                                          | ""
    "Sqs"      | "CreateQueue"       | "java-aws-sdk" | "POST" | "/"                   | "7a62c49f-347e-4fc4-9331-6e8e7a96aa73" | SqsAsyncClient.builder()      | { c -> c.createQueue(CreateQueueRequest.builder().queueName("somequeue").build()) }                                              | """
        <CreateQueueResponse>
            <CreateQueueResult><QueueUrl>https://queue.amazonaws.com/123456789012/MyQueue</QueueUrl></CreateQueueResult>
            <ResponseMetadata><RequestId>7a62c49f-347e-4fc4-9331-6e8e7a96aa73</RequestId></ResponseMetadata>
        </CreateQueueResponse>
        """
    "Sqs"      | "SendMessage"       | "sqs"          | "POST" | "/"                   | "27daac76-34dd-47df-bd01-1f6e873584a0" | SqsAsyncClient.builder()      | { c -> c.sendMessage(SendMessageRequest.builder().queueUrl("someurl").messageBody("").build()) }                                 | """
        <SendMessageResponse>
            <SendMessageResult>
                <MD5OfMessageBody>d41d8cd98f00b204e9800998ecf8427e</MD5OfMessageBody>
                <MD5OfMessageAttributes>3ae8f24a165a8cedc005670c81a27295</MD5OfMessageAttributes>
                <MessageId>5fea7756-0ea4-451a-a703-a558b933e274</MessageId>
            </SendMessageResult>
            <ResponseMetadata><RequestId>27daac76-34dd-47df-bd01-1f6e873584a0</RequestId></ResponseMetadata>
        </SendMessageResponse>
        """
    "Sns"      | "Publish"           | "sns"          | "POST" | "/"                   | "d74b8436-ae13-5ab4-a9ff-ce54dfea72a0" | SnsAsyncClient.builder()      | { c -> c.publish(PublishRequest.builder().topicArn("arn:aws:sns::123:some-topic").message("").build()) } | """
        <PublishResponse xmlns="https://sns.amazonaws.com/doc/2010-03-31/">
            <PublishResult>
                <MessageId>567910cd-659e-55d4-8ccb-5aaf14679dc0</MessageId>
            </PublishResult>
            <ResponseMetadata><RequestId>d74b8436-ae13-5ab4-a9ff-ce54dfea72a0</RequestId></ResponseMetadata>
        </PublishResponse>
        """
    "Ec2"      | "AllocateAddress"   | "java-aws-sdk" | "POST" | "/"                   | "59dbff89-35bd-4eac-99ed-be587EXAMPLE" | Ec2AsyncClient.builder()      | { c -> c.allocateAddress() }                                                                                                     | """
        <AllocateAddressResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
           <requestId>59dbff89-35bd-4eac-99ed-be587EXAMPLE</requestId> 
           <publicIp>192.0.2.1</publicIp>
           <domain>standard</domain>
        </AllocateAddressResponse>
        """
    "Rds"      | "DeleteOptionGroup" | "java-aws-sdk" | "POST" | "/"                   | "0ac9cda2-bbf4-11d3-f92b-31fa5e8dbc99" | RdsAsyncClient.builder()      | { c -> c.deleteOptionGroup(DeleteOptionGroupRequest.builder().build()) }                                                         | """
        <DeleteOptionGroupResponse xmlns="http://rds.amazonaws.com/doc/2014-09-01/">
          <ResponseMetadata><RequestId>0ac9cda2-bbf4-11d3-f92b-31fa5e8dbc99</RequestId></ResponseMetadata>
        </DeleteOptionGroupResponse>
        """
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
    def client = S3Client.builder()
      .endpointOverride(server.address)
      .region(Region.AP_NORTHEAST_1)
      .credentialsProvider(CREDENTIALS_PROVIDER)
      .httpClientBuilder(ApacheHttpClient.builder().socketTimeout(Duration.ofMillis(50)))
      .build()

    when:
    client.getObject(GetObjectRequest.builder().bucket("somebucket").key("somekey").build())

    then:
    thrown SdkClientException

    assertTraces(1) {
      trace(5) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.GetObject"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "$server.address/somebucket/somekey"
            "$Tags.HTTP_METHOD" "GET"
            "aws.service" "S3"
            "aws_service" "S3"
            "aws.operation" "GetObject"
            "aws.agent" "java-aws-sdk"
            "aws.bucket.name" "somebucket"
            "bucketname" "somebucket"
            "aws.object.key" "somekey"
            errorTags SdkClientException, "Unable to execute HTTP request: Read timed out"
            defaultTags()
          }
        }
        (1..4).each {
          span {
            operationName "http.request"
            resourceName "GET /somebucket/somekey"
            spanType DDSpanTypes.HTTP_CLIENT
            errored true
            childOf(span(0))
            tags {
              "$Tags.COMPONENT" "apache-httpclient"
              "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
              "$Tags.PEER_HOSTNAME" "localhost"
              "$Tags.PEER_PORT" server.address.port
              "$Tags.HTTP_URL" "$server.address/somebucket/somekey"
              "$Tags.HTTP_METHOD" "GET"
              errorTags SocketTimeoutException, "Read timed out"
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
