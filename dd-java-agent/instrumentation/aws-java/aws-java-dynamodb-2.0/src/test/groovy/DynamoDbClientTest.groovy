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
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeAction
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.AttributeValueUpdate
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest
import software.amazon.awssdk.services.dynamodb.model.ExpectedAttributeValue
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import spock.lang.Shared

import java.time.Duration

class DynamoDbClientTest extends InstrumentationSpecification {
  static final LOCALSTACK = new GenericContainer(DockerImageName.parse("localstack/localstack:4.2.0"))
  .withExposedPorts(4566)
  .withEnv("SERVICES", "dynamodb")
  .withReuse(true)
  .withStartupTimeout(Duration.ofSeconds(120))

  @Shared
  DynamoDbClient dynamoDbClient

  @Shared
  String tableNameOneKey

  @Shared
  String tableNameTwoKeys

  def setupSpec() {
    LOCALSTACK.start()
    def endPoint = "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)
    dynamoDbClient = DynamoDbClient.builder()
      .endpointOverride(URI.create(endPoint))
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
      .build()

    // Create test tables
    tableNameOneKey = "dynamodb-one-key-table"
    tableNameTwoKeys = "dynamodb-two-key-table"

    // Create one-key table
    CreateTableRequest oneKeyTableRequest = CreateTableRequest.builder()
      .tableName(tableNameOneKey)
      .keySchema(
      KeySchemaElement.builder()
      .attributeName("id")
      .keyType(KeyType.HASH)
      .build()
      )
      .attributeDefinitions(
      AttributeDefinition.builder()
      .attributeName("id")
      .attributeType(ScalarAttributeType.S)
      .build()
      )
      .provisionedThroughput(
      ProvisionedThroughput.builder()
      .readCapacityUnits(5L)
      .writeCapacityUnits(5L)
      .build()
      )
      .build()
    dynamoDbClient.createTable(oneKeyTableRequest)

    // Create two-key table
    List<KeySchemaElement> twoKeyTableSchema = new ArrayList<>()
    twoKeyTableSchema.add(KeySchemaElement.builder()
      .attributeName("primaryKey")
      .keyType(KeyType.HASH)
      .build())
    twoKeyTableSchema.add(KeySchemaElement.builder()
      .attributeName("sortKey")
      .keyType(KeyType.RANGE)
      .build())

    List<AttributeDefinition> twoKeyTableAttributeDefs = new ArrayList<>()
    twoKeyTableAttributeDefs.add(AttributeDefinition.builder()
      .attributeName("primaryKey")
      .attributeType(ScalarAttributeType.S)
      .build())
    twoKeyTableAttributeDefs.add(AttributeDefinition.builder()
      .attributeName("sortKey")
      .attributeType(ScalarAttributeType.S)
      .build())

    CreateTableRequest twoKeyTableRequest = CreateTableRequest.builder()
      .tableName(tableNameTwoKeys)
      .keySchema(twoKeyTableSchema)
      .attributeDefinitions(twoKeyTableAttributeDefs)
      .provisionedThroughput(
      ProvisionedThroughput.builder()
      .readCapacityUnits(5L)
      .writeCapacityUnits(5L)
      .build()
      )
      .build()
    dynamoDbClient.createTable(twoKeyTableRequest)
  }

  def cleanupSpec() {
    DeleteTableRequest deleteOneKeyRequest = DeleteTableRequest.builder().tableName(tableNameOneKey).build()
    DeleteTableRequest deleteTwoKeyRequest = DeleteTableRequest.builder().tableName(tableNameTwoKeys).build()
    dynamoDbClient.deleteTable(deleteOneKeyRequest)
    dynamoDbClient.deleteTable(deleteTwoKeyRequest)
    LOCALSTACK.stop()
  }

  def "should add span pointer for updateItem operation on one-key table"() {
    when:
    // First, put an item
    Map<String, AttributeValue> item = new HashMap<>()
    item.put("id", AttributeValue.builder().s("test-id-1").build())
    item.put("data", AttributeValue.builder().s("initial-value").build())

    PutItemRequest putRequest = PutItemRequest.builder()
      .tableName(tableNameOneKey)
      .item(item)
      .build()
    dynamoDbClient.putItem(putRequest)

    // Then update the item
    Map<String, AttributeValue> key = new HashMap<>()
    key.put("id", AttributeValue.builder().s("test-id-1").build())

    Map<String, AttributeValueUpdate> updates = new HashMap<>()
    updates.put("data", AttributeValueUpdate.builder()
      .value(AttributeValue.builder().s("updated-value").build())
      .action(AttributeAction.PUT)
      .build())
    updates.put("counter", AttributeValueUpdate.builder()
      .value(AttributeValue.builder().n("1").build())
      .action(AttributeAction.PUT)
      .build())

    UpdateItemRequest updateRequest = UpdateItemRequest.builder()
      .tableName(tableNameOneKey)
      .key(key)
      .attributeUpdates(updates)
      .build()
    dynamoDbClient.updateItem(updateRequest)

    then:
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "DynamoDb.PutItem"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "PutItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameOneKey
            tag "tablename", tableNameOneKey
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
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
          resourceName "DynamoDb.UpdateItem"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, 0, SpanLink.DEFAULT_FLAGS, SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.DYNAMODB_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              // First 32 chars of SHA256("dynamodb-one-key-table|id|test-id-1||")
              .put("ptr.hash", "ca8daaa857b00545ed5186a915cf1ab5")
              .put("link.kind", SpanPointersProcessor.LINK_KIND)
              .build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "UpdateItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameOneKey
            tag "tablename", tableNameOneKey
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "_dd.span_links", { it != null }
          }
        }
      }
    }
  }

  def "should add span pointer for updateItem operation on two-key table"() {
    when:
    // First, put an item
    Map<String, AttributeValue> item = new HashMap<>()
    item.put("primaryKey", AttributeValue.builder().s("customer-123").build())
    item.put("sortKey", AttributeValue.builder().s("order-456").build())
    item.put("status", AttributeValue.builder().s("pending").build())
    item.put("total", AttributeValue.builder().n("99.99").build())

    PutItemRequest putTwoKeyRequest = PutItemRequest.builder()
      .tableName(tableNameTwoKeys)
      .item(item)
      .build()
    dynamoDbClient.putItem(putTwoKeyRequest)

    // Then update the item
    Map<String, AttributeValue> key = new HashMap<>()
    key.put("primaryKey", AttributeValue.builder().s("customer-123").build())
    key.put("sortKey", AttributeValue.builder().s("order-456").build())

    Map<String, AttributeValueUpdate> updates = new HashMap<>()
    updates.put("status", AttributeValueUpdate.builder()
      .value(AttributeValue.builder().s("shipped").build())
      .action(AttributeAction.PUT)
      .build())
    updates.put("total", AttributeValueUpdate.builder()
      .value(AttributeValue.builder().n("129.99").build())
      .action(AttributeAction.PUT)
      .build())

    UpdateItemRequest updateTwoKeyRequest = UpdateItemRequest.builder()
      .tableName(tableNameTwoKeys)
      .key(key)
      .attributeUpdates(updates)
      .build()
    dynamoDbClient.updateItem(updateTwoKeyRequest)

    then:
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "DynamoDb.PutItem"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "PutItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameTwoKeys
            tag "tablename", tableNameTwoKeys
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
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
          resourceName "DynamoDb.UpdateItem"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, 0, SpanLink.DEFAULT_FLAGS, SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.DYNAMODB_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              // First 32 chars of SHA256("dynamodb-two-key-table|primaryKey|customer-123|sortKey|order-456")
              .put("ptr.hash", "90922c7899a82ea34406fdcdfb95161e")
              .put("link.kind", SpanPointersProcessor.LINK_KIND)
              .build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "UpdateItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameTwoKeys
            tag "tablename", tableNameTwoKeys
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "_dd.span_links", { it != null }
          }
        }
      }
    }
  }

  def "should add span pointer for deleteItem operation on one-key table"() {
    when:
    // First, put an item
    Map<String, AttributeValue> item = new HashMap<>()
    item.put("id", AttributeValue.builder().s("delete-test-id").build())
    item.put("data", AttributeValue.builder().s("to-be-deleted").build())

    PutItemRequest putDelTestRequest = PutItemRequest.builder()
      .tableName(tableNameOneKey)
      .item(item)
      .build()
    dynamoDbClient.putItem(putDelTestRequest)

    // Then delete the item
    Map<String, AttributeValue> key = new HashMap<>()
    key.put("id", AttributeValue.builder().s("delete-test-id").build())

    DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
      .tableName(tableNameOneKey)
      .key(key)
      .build()
    dynamoDbClient.deleteItem(deleteRequest)

    then:
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "DynamoDb.PutItem"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "PutItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameOneKey
            tag "tablename", tableNameOneKey
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
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
          resourceName "DynamoDb.DeleteItem"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, 0, SpanLink.DEFAULT_FLAGS, SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.DYNAMODB_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              // First 32 chars of SHA256("dynamodb-one-key-table|id|delete-test-id||")
              .put("ptr.hash", "65031164be5e929fddd274a02cba3f9f")
              .put("link.kind", SpanPointersProcessor.LINK_KIND)
              .build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "DeleteItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameOneKey
            tag "tablename", tableNameOneKey
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "_dd.span_links", { it != null }
          }
        }
      }
    }
  }

  def "should add span pointer for deleteItem operation on two-key table"() {
    when:
    // First, put an item
    Map<String, AttributeValue> item = new HashMap<>()
    item.put("primaryKey", AttributeValue.builder().s("user-789").build())
    item.put("sortKey", AttributeValue.builder().s("profile").build())
    item.put("status", AttributeValue.builder().s("active").build())
    item.put("lastLogin", AttributeValue.builder().s("2023-01-01").build())

    PutItemRequest putProfileRequest = PutItemRequest.builder()
      .tableName(tableNameTwoKeys)
      .item(item)
      .build()
    dynamoDbClient.putItem(putProfileRequest)

    // Then delete the item
    Map<String, AttributeValue> key = new HashMap<>()
    key.put("primaryKey", AttributeValue.builder().s("user-789").build())
    key.put("sortKey", AttributeValue.builder().s("profile").build())

    // Use expected instead of conditionExpression
    Map<String, ExpectedAttributeValue> expected = new HashMap<>()
    expected.put("status",
      ExpectedAttributeValue.builder()
      .value(AttributeValue.builder().s("active").build())
      .build())

    DeleteItemRequest deleteWithExpectedRequest = DeleteItemRequest.builder()
      .tableName(tableNameTwoKeys)
      .key(key)
      .expected(expected)
      .build()
    dynamoDbClient.deleteItem(deleteWithExpectedRequest)

    then:
    assertTraces(2) {
      trace(1) {
        span {
          serviceName "java-aws-sdk"
          operationName "aws.http"
          resourceName "DynamoDb.PutItem"
          spanType DDSpanTypes.HTTP_CLIENT
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "PutItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameTwoKeys
            tag "tablename", tableNameTwoKeys
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
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
          resourceName "DynamoDb.DeleteItem"
          spanType DDSpanTypes.HTTP_CLIENT
          links {
            link(DDTraceId.ZERO, 0, SpanLink.DEFAULT_FLAGS, SpanAttributes.builder()
              .put("ptr.kind", SpanPointersProcessor.DYNAMODB_PTR_KIND)
              .put("ptr.dir", SpanPointersProcessor.DOWN_DIRECTION)
              // First 32 chars of SHA256("dynamodb-two-key-table|primaryKey|user-789|sortKey|profile")
              .put("ptr.hash", "e5ce1148208c6f88041c73ceb9bbbf3a")
              .put("link.kind", SpanPointersProcessor.LINK_KIND)
              .build())
          }
          tags {
            defaultTags()
            tag "component", "java-aws-sdk"
            tag "aws.operation", "DeleteItem"
            tag "aws.service", "DynamoDb"
            tag "aws_service", "DynamoDb"
            tag "aws.agent", "java-aws-sdk"
            tag "aws.table.name", tableNameTwoKeys
            tag "tablename", tableNameTwoKeys
            tag "http.method", "POST"
            tag "http.status_code", 200
            tag "http.url", { it.startsWith("http://") }
            tag "peer.hostname", { it != null }
            tag "peer.port", { it instanceof Integer }
            tag "span.kind", "client"
            tag "aws.requestId", { it != null }
            tag "_dd.span_links", { it != null }
          }
        }
      }
    }
  }
}
