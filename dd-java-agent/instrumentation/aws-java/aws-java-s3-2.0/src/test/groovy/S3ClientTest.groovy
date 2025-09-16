import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes
import datadog.trace.bootstrap.instrumentation.api.SpanLink
import datadog.trace.core.tagprocessor.SpanPointersProcessor
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.S3Configuration
import spock.lang.Shared

import java.time.Duration

class S3ClientTest extends InstrumentationSpecification {
  static final LOCALSTACK = new GenericContainer(DockerImageName.parse("localstack/localstack:4.2.0"))
  .withExposedPorts(4566)
  .withEnv("SERVICES", "s3")
  .withReuse(true)
  .withStartupTimeout(Duration.ofSeconds(120))

  @Shared
  S3Client s3Client

  @Shared
  String bucketName

  def setupSpec() {
    LOCALSTACK.start()
    def endPoint = "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)

    s3Client = S3Client.builder()
      .endpointOverride(URI.create(endPoint))
      .region(Region.of("us-east-1"))
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .serviceConfiguration(S3Configuration.builder()
      .pathStyleAccessEnabled(true)
      .checksumValidationEnabled(false)
      .build())
      .build()

    // Create a test bucket
    bucketName = "s3-bucket-name-test"
    s3Client.createBucket { it.bucket(bucketName) }
  }

  def cleanupSpec() {
    LOCALSTACK.stop()
  }

  def "should add span pointer for putObject operation"() {
    when:
    TEST_WRITER.clear()
    String key = "test-key"
    String content = "test body"

    s3Client.putObject(
      PutObjectRequest.builder().bucket(bucketName).key(key).build(),
      RequestBody.fromString(content)
      )

    then:
    assertTraces(1) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.PutObject"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, (long)0, SpanLink.DEFAULT_FLAGS,
              SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.S3_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              .put("ptr.hash","6d1a2fe194c6579187408f827f942be3")
              .put("link.kind",SpanPointersProcessor.LINK_KIND).build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "PutObject"
            tag "aws.service", "S3"
            tag "aws_service", "S3"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.bucket.name", bucketName
            tag "aws.object.key", key
            tag "bucketname", bucketName
            tag "http.method", "PUT"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://" + LOCALSTACK.getHost()) && it.contains("/$key") }
            tag "peer.hostname", LOCALSTACK.getHost()
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
          }
        }
      }
    }
  }

  def "should add span pointer for copyObject operation"() {
    when:
    TEST_WRITER.clear()
    String sourceKey = "test-key"
    String destKey = "new-key"
    String content = "test body"

    s3Client.putObject(
      PutObjectRequest.builder().bucket(bucketName).key(sourceKey).build(),
      RequestBody.fromString(content)
      )
    s3Client.copyObject(
      CopyObjectRequest.builder()
      .sourceBucket(bucketName)
      .destinationBucket(bucketName)
      .sourceKey(sourceKey)
      .destinationKey(destKey)
      .build()
      )

    then:
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.PutObject"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, (long)0, SpanLink.DEFAULT_FLAGS,
              SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.S3_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              .put("ptr.hash","6d1a2fe194c6579187408f827f942be3")
              .put("link.kind",SpanPointersProcessor.LINK_KIND).build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "PutObject"
            tag "aws.service", "S3"
            tag "aws_service", "S3"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.bucket.name", bucketName
            tag "aws.object.key", sourceKey
            tag "bucketname", bucketName
            tag "http.method", "PUT"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://" + LOCALSTACK.getHost()) && it.contains("/$sourceKey") }
            tag "peer.hostname", LOCALSTACK.getHost()
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
          }
        }
      }
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.CopyObject"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, (long)0, SpanLink.DEFAULT_FLAGS,
              SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.S3_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              .put("ptr.hash","1542053ce6d393c424b1374bac1fc0c5")
              .put("link.kind",SpanPointersProcessor.LINK_KIND).build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "CopyObject"
            tag "aws.service", "S3"
            tag "aws_service", "S3"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.bucket.name", bucketName
            tag "aws.object.key", destKey
            tag "bucketname", bucketName
            tag "http.method", "PUT"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://" + LOCALSTACK.getHost()) && it.contains("/$destKey") }
            tag "peer.hostname", LOCALSTACK.getHost()
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
          }
        }
      }
    }
  }

  def "should add span pointer for completeMultipartUpload operation"() {
    when:
    TEST_WRITER.clear()
    String key = "multipart-test"

    // Initiate multipart upload
    def createMultipartUploadResponse = s3Client.createMultipartUpload(
      CreateMultipartUploadRequest.builder().bucket(bucketName).key(key).build()
      )
    String uploadId = createMultipartUploadResponse.uploadId()

    // Create parts (5MB each)
    int partSize = 5 * 1024 * 1024
    byte[] part1Data = new byte[partSize]
    Arrays.fill(part1Data, (byte) 'a')
    byte[] part2Data = new byte[partSize]
    Arrays.fill(part2Data, (byte) 'b')

    // Upload parts
    List<CompletedPart> completedParts = []
    s3Client.uploadPart(
      UploadPartRequest.builder()
      .bucket(bucketName)
      .key(key)
      .uploadId(uploadId)
      .partNumber(1)
      .build(),
      RequestBody.fromBytes(part1Data)
      ).with { response ->
        completedParts.add(CompletedPart.builder()
          .partNumber(1)
          .eTag(response.eTag())
          .build())
      }
    s3Client.uploadPart(
      UploadPartRequest.builder()
      .bucket(bucketName)
      .key(key)
      .uploadId(uploadId)
      .partNumber(2)
      .build(),
      RequestBody.fromBytes(part2Data)
      ).with { response ->
        completedParts.add(CompletedPart.builder()
          .partNumber(2)
          .eTag(response.eTag())
          .build())
      }

    // Complete multipart upload
    s3Client.completeMultipartUpload(
      CompleteMultipartUploadRequest.builder()
      .bucket(bucketName)
      .key(key)
      .uploadId(uploadId)
      .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
      .build()
      )

    then:
    // Check that spans were created and that the CompleteMultipartUpload span has the expected span pointer
    assertTraces(4) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.CreateMultipartUpload"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "CreateMultipartUpload"
            tag "aws.service", "S3"
            tag "aws_service", "S3"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.bucket.name", bucketName
            tag "aws.object.key", key
            tag "bucketname", bucketName
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://" + LOCALSTACK.getHost()) && it.contains("/$key") }
            tag "peer.hostname", LOCALSTACK.getHost()
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "http.query.string", "uploads"
          }
        }
      }
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.UploadPart"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "UploadPart"
            tag "aws.service", "S3"
            tag "aws_service", "S3"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.bucket.name", bucketName
            tag "aws.object.key", key
            tag "bucketname", bucketName
            tag "http.method", "PUT"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://" + LOCALSTACK.getHost()) && it.contains("/$key") }
            tag "peer.hostname", LOCALSTACK.getHost()
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "http.query.string", { it != null }
          }
        }
      }
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.UploadPart"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "UploadPart"
            tag "aws.service", "S3"
            tag "aws_service", "S3"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.bucket.name", bucketName
            tag "aws.object.key", key
            tag "bucketname", bucketName
            tag "http.method", "PUT"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://" + LOCALSTACK.getHost()) && it.contains("/$key") }
            tag "peer.hostname", LOCALSTACK.getHost()
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "http.query.string", { it != null }
          }
        }
      }
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "S3.CompleteMultipartUpload"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, (long)0, SpanLink.DEFAULT_FLAGS,
              SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.S3_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              .put("ptr.hash","422412aa6b472a7194f3e24f4b12b4a6")
              .put("link.kind",SpanPointersProcessor.LINK_KIND).build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "CompleteMultipartUpload"
            tag "aws.service", "S3"
            tag "aws_service", "S3"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.bucket.name", bucketName
            tag "aws.object.key", key
            tag "bucketname", bucketName
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://" + LOCALSTACK.getHost()) && it.contains("/$key") }
            tag "peer.hostname", LOCALSTACK.getHost()
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "http.query.string", { it != null }
          }
        }
      }
    }
  }
}
