import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink
import datadog.trace.bootstrap.instrumentation.api.SpanPointerUtils
import datadog.trace.instrumentation.aws.v2.dynamodb.DynamoDbUtil
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

class DynamoDbUtilTest {
  static createMockSpan() {
    def links = []

    def mockSpan = [
      addLink: { AgentSpanLink link ->
        links.add(link)
      }
    ] as AgentSpan

    return [span: mockSpan, links: links]
  }

  @Test
  void testAddSpanPointerWithNullKeys() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def links = mockData.links

    DynamoDbUtil.addSpanPointer(mockSpan, "table", null)

    assert links.isEmpty()
  }

  @Test
  void testAddSpanPointerWithEmptyKeys() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def links = mockData.links

    DynamoDbUtil.addSpanPointer(mockSpan, "table", [:])

    assert links.isEmpty()
  }

  @Test
  void testAddSpanPointerWithNullTableName() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def links = mockData.links

    def keys = [
      "id": AttributeValue.builder().s("12345").build()
    ]

    DynamoDbUtil.addSpanPointer(mockSpan, null, keys)

    assert links.isEmpty()
  }

  @Test
  void testAddSpanPointerWithSingleStringKey() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def links = mockData.links

    def keys = [
      "id": AttributeValue.builder().s("12345").build()
    ]

    DynamoDbUtil.addSpanPointer(mockSpan, "my-table", keys)

    assert links.size() == 1
    def link = links[0]
    assert link.attributes().asMap().get("ptr.kind") == SpanPointerUtils.DYNAMODB_PTR_KIND
    assert link.attributes().asMap().get("ptr.dir") == SpanPointerUtils.DOWN_DIRECTION
    assert link.attributes().asMap().get("link.kind") == SpanPointerUtils.LINK_KIND
    assert link.attributes().asMap().get("ptr.hash") != null
  }

  @Test
  void testAddSpanPointerWithSingleNumberKey() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def links = mockData.links

    def keys = [
      "count": AttributeValue.builder().n("42").build()
    ]

    DynamoDbUtil.addSpanPointer(mockSpan, "my-table", keys)

    assert links.size() == 1
    def link = links[0]
    assert link.attributes().asMap().get("ptr.kind") == SpanPointerUtils.DYNAMODB_PTR_KIND
  }

  @Test
  void testAddSpanPointerWithSingleBinaryKey() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def links = mockData.links

    def binaryData = "binary-data".getBytes()
    def keys = [
      "data": AttributeValue.builder().b(SdkBytes.fromByteArray(binaryData)).build()
    ]

    DynamoDbUtil.addSpanPointer(mockSpan, "my-table", keys)

    assert links.size() == 1
    def link = links[0]
    assert link.attributes().asMap().get("ptr.kind") == SpanPointerUtils.DYNAMODB_PTR_KIND
  }

  @Test
  void testAddSpanPointerWithTwoKeys() {
    def mockData = createMockSpan()
    def mockSpan = mockData.span
    def links = mockData.links

    def keys = [
      "id": AttributeValue.builder().s("12345").build(),
      "name": AttributeValue.builder().s("item-name").build()
    ]

    DynamoDbUtil.addSpanPointer(mockSpan, "my-table", keys)

    assert links.size() == 1
    def link = links[0]
    assert link.attributes().asMap().get("ptr.kind") == SpanPointerUtils.DYNAMODB_PTR_KIND
    assert link.attributes().asMap().get("ptr.hash") != null
  }

  @Test
  void testAddSpanPointerWithTwoKeysSortsAlphabetically() {
    def mockData = createMockSpan()
    def mockSpan1 = mockData.span
    def links1 = mockData.links

    // Keys in order bKey, aKey
    def keys1 = [
      "bKey": AttributeValue.builder().s("abc").build(),
      "aKey": AttributeValue.builder().s("zxy").build()
    ]

    DynamoDbUtil.addSpanPointer(mockSpan1, "my-table", keys1)

    // Reverse order: aKey, bKey — should produce the same hash
    def mockData2 = createMockSpan()
    def mockSpan2 = mockData2.span
    def links2 = mockData2.links

    def keys2 = [
      "aKey": AttributeValue.builder().s("zxy").build(),
      "bKey": AttributeValue.builder().s("abc").build()
    ]

    DynamoDbUtil.addSpanPointer(mockSpan2, "my-table", keys2)

    assert links1.size() == 1
    assert links2.size() == 1
    // Both should produce the same hash regardless of input order
    assert links1[0].attributes().asMap().get("ptr.hash") == links2[0].attributes().asMap().get("ptr.hash")
  }
}
