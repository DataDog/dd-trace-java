import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.instrumentation.aws.v2.dynamodb.DynamoDbUtil
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class DynamoDbUtilTest {
  static createMockSpan() {
    def tags = [:]

    def mockSpan = [
      setTag: { String key, String value ->
        tags[key] = value
        return null
      }
    ] as AgentSpan

    return [span: mockSpan, tags: tags]
  }

  @Test
  void testExportTagsWithNullKeys() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def tags = mockData.tags

    DynamoDbUtil.exportTagsWithKnownKeys(mockSpan, null)

    assert tags.isEmpty()
  }

  @Test
  void testExportTagsWithEmptyKeys() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def tags = mockData.tags

    DynamoDbUtil.exportTagsWithKnownKeys(mockSpan, [:])

    assert tags.isEmpty()
  }

  @Test
  void testExportTagsWithSingleStringKey() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def tags = mockData.tags

    def keys = [
      "id": AttributeValue.builder().s("12345").build()
    ]

    DynamoDbUtil.exportTagsWithKnownKeys(mockSpan, keys)

    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1] == "id"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE] == "12345"
  }

  @Test
  void testExportTagsWithSingleNumberKey() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def tags = mockData.tags

    def keys = [
      "count": AttributeValue.builder().n("42").build()
    ]

    DynamoDbUtil.exportTagsWithKnownKeys(mockSpan, keys)

    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1] == "count"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE] == "42"
  }

  @Test
  void testExportTagsWithSingleBinaryKey() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def tags = mockData.tags

    def binaryData = "binary-data".getBytes()
    def keys = [
      "data": AttributeValue.builder().b(SdkBytes.fromByteArray(binaryData)).build()
    ]

    DynamoDbUtil.exportTagsWithKnownKeys(mockSpan, keys)

    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1] == "data"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE] == "binary-data"
  }

  @Test
  void testExportTagsWithTwoKeys() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def tags = mockData.tags

    def keys = [
      "id": AttributeValue.builder().s("12345").build(),
      "name": AttributeValue.builder().s("item-name").build()
    ]

    DynamoDbUtil.exportTagsWithKnownKeys(mockSpan, keys)

    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1] == "id"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE] == "12345"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_2] == "name"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_2_VALUE] == "item-name"
  }

  @Test
  void testExportTagsWithTwoKeysSortsAlphabetically() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def tags = mockData.tags

    def keys = [
      "bKey": AttributeValue.builder().s("abc").build(),
      "aKey": AttributeValue.builder().s("zxy").build()
    ]

    DynamoDbUtil.exportTagsWithKnownKeys(mockSpan, keys)

    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1] == "aKey"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_1_VALUE] == "zxy"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_2] == "bKey"
    assert tags[InstrumentationTags.DYNAMO_PRIMARY_KEY_2_VALUE] == "abc"
  }
}
