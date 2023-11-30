import com.amazonaws.AmazonClientException
import com.amazonaws.ClientConfiguration
import com.amazonaws.Request
import com.amazonaws.SDKGlobalConfiguration
import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider
import com.amazonaws.auth.InstanceProfileCredentialsProvider
import com.amazonaws.auth.SystemPropertiesCredentialsProvider
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.handlers.RequestHandler2
import com.amazonaws.retry.PredefinedRetryPolicies
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.rds.AmazonRDSClient
import com.amazonaws.services.rds.model.DeleteOptionGroupRequest
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.S3ClientOptions
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class AWS0ClientTest extends AgentTestRunner {

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

    when:
    def response = call.call(client)

    then:
    response != null

    client.requestHandler2s != null
    client.requestHandler2s.size() == handlerCount
    client.requestHandler2s.get(handlerCount - 1).getClass().getSimpleName() == "TracingRequestHandler"

    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
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
      }
    }
    server.lastRequest.headers.get("x-datadog-trace-id") == null
    server.lastRequest.headers.get("x-datadog-parent-id") == null

    where:
    service | operation           | method | path                  | handlerCount | client                                                                      | additionalTags                    | call                                                                                                                         | body
    "S3"    | "CreateBucket"      | "PUT"  | "/testbucket/"        | 1            | new AmazonS3Client().withEndpoint("http://localhost:$server.address.port")  | ["aws.bucket.name": "testbucket", "bucketname": "testbucket"] | { c -> c.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build()); client.createBucket("testbucket") } | ""
    "S3"    | "GetObject"         | "GET"  | "/someBucket/someKey" | 1            | new AmazonS3Client().withEndpoint("http://localhost:$server.address.port")  | ["aws.bucket.name": "someBucket", "bucketname": "someBucket"] | { c -> c.getObject("someBucket", "someKey") }                                                                                | ""
    "EC2"   | "AllocateAddress"   | "POST" | "/"                   | 4            | new AmazonEC2Client().withEndpoint("http://localhost:$server.address.port") | [:]                               | { c -> c.allocateAddress() }                                                                                                 | """
            <AllocateAddressResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
               <requestId>59dbff89-35bd-4eac-99ed-be587EXAMPLE</requestId> 
               <publicIp>192.0.2.1</publicIp>
               <domain>standard</domain>
            </AllocateAddressResponse>
            """
    "RDS"   | "DeleteOptionGroup" | "POST" | "/"                   | 1            | new AmazonRDSClient().withEndpoint("http://localhost:$server.address.port") | [:]                               | { c -> c.deleteOptionGroup(new DeleteOptionGroupRequest()) }                                                                 | """
        <DeleteOptionGroupResponse xmlns="http://rds.amazonaws.com/doc/2014-09-01/">
          <ResponseMetadata>
            <RequestId>0ac9cda2-bbf4-11d3-f92b-31fa5e8dbc99</RequestId>
          </ResponseMetadata>
        </DeleteOptionGroupResponse>
      """
  }

  def "send #operation request to closed port"() {
    setup:
    responseBody.set(body)

    when:
    call.call(client)

    then:
    thrown AmazonClientException

    assertTraces(1) {
      trace(1) {
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
            "$Tags.PEER_PORT" 61
            "$Tags.PEER_HOSTNAME" "localhost"
            "aws.service" { it.contains(service) }
            "aws_service" { it.contains(service.toLowerCase()) }
            "aws.endpoint" "http://localhost:${UNUSABLE_PORT}"
            "aws.operation" "${operation}Request"
            "aws.agent" "java-aws-sdk"
            for (def addedTag : additionalTags) {
              "$addedTag.key" "$addedTag.value"
            }
            errorTags AmazonClientException, ~/Unable to execute HTTP request/
            defaultTags()
          }
        }
      }
    }

    where:
    service | operation   | method | url                  | call                                                    | additionalTags                    | body | client
    "S3" | "GetObject" | "GET" | "someBucket/someKey" | { c -> c.getObject("someBucket", "someKey") } | ["aws.bucket.name": "someBucket", "bucketname": "someBucket"] | "" | new AmazonS3Client(CREDENTIALS_PROVIDER_CHAIN, new ClientConfiguration().withRetryPolicy(PredefinedRetryPolicies.getDefaultRetryPolicyWithCustomMaxRetries(0))).withEndpoint("http://localhost:${UNUSABLE_PORT}")
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
          resourceName "S3.GetObject"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          measured true
          parent()
          tags {
            "$Tags.COMPONENT" "java-aws-sdk"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "https://s3.amazonaws.com/"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.PEER_HOSTNAME" "s3.amazonaws.com"
            "aws.service" "Amazon S3"
            "aws_service" "s3"
            "aws.endpoint" "https://s3.amazonaws.com"
            "aws.operation" "GetObjectRequest"
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
      trace(1) {
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
            "aws.endpoint" "http://localhost:$server.address.port"
            "aws.operation" "GetObjectRequest"
            "aws.agent" "java-aws-sdk"
            "aws.bucket.name" "someBucket"
            "bucketname" "someBucket"
            errorTags AmazonClientException, ~/Unable to execute HTTP request/
            defaultTags()
          }
        }
      }
    }

    cleanup:
    server.close()
  }
}
