package datadog.trace.bootstrap.instrumentation.api

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.test.util.DDSpecification

class SpanPointerUtilsTest extends DDSpecification {
  def "generatePointerHash produces correct hash for S3 basic values"() {
    when:
    def hash = SpanPointerUtils.generatePointerHash("some-bucket", "some-key.data", "ab12ef34")

    then:
    hash == "e721375466d4116ab551213fdea08413"
  }

  def "generatePointerHash produces correct hash for S3 non-ascii key"() {
    when:
    def hash = SpanPointerUtils.generatePointerHash("some-bucket", "some-key.\u4f60\u597d", "ab12ef34")

    then:
    hash == "d1333a04b9928ab462b5c6cadfa401f4"
  }

  def "generatePointerHash produces correct hash for S3 multipart-upload ETag"() {
    when:
    def hash = SpanPointerUtils.generatePointerHash("some-bucket", "some-key.data", "ab12ef34-5")

    then:
    hash == "2b90dffc37ebc7bc610152c3dc72af9f"
  }

  def "buildSpanPointer creates link with correct attributes"() {
    when:
    def link = SpanPointerUtils.buildSpanPointer("testhash", SpanPointerUtils.S3_PTR_KIND)

    then:
    link instanceof SpanLink
    link.traceId() == DDTraceId.ZERO
    link.spanId() == DDSpanId.ZERO
    link.attributes().asMap().get("ptr.kind") == SpanPointerUtils.S3_PTR_KIND
    link.attributes().asMap().get("ptr.dir") == SpanPointerUtils.DOWN_DIRECTION
    link.attributes().asMap().get("ptr.hash") == "testhash"
    link.attributes().asMap().get("link.kind") == SpanPointerUtils.LINK_KIND
  }

  def "addS3SpanPointer strips surrounding quotes from eTag"() {
    given:
    def links = []
    def span = Mock(AgentSpan)
    span.addLink(_) >> { AgentSpanLink link -> links.add(link) }

    when:
    SpanPointerUtils.addS3SpanPointer(span, "some-bucket", "some-key.data", '"ab12ef34"')

    then:
    links.size() == 1
    // Quoted eTag should produce the same hash as unquoted
    links[0].attributes().asMap().get("ptr.hash") == "e721375466d4116ab551213fdea08413"
  }

  def "addS3SpanPointer skips when bucket is null"() {
    given:
    def span = Mock(AgentSpan)

    when:
    SpanPointerUtils.addS3SpanPointer(span, null, "key", "etag")

    then:
    0 * span.addLink(_)
  }

  def "addS3SpanPointer skips when key is null"() {
    given:
    def span = Mock(AgentSpan)

    when:
    SpanPointerUtils.addS3SpanPointer(span, "bucket", null, "etag")

    then:
    0 * span.addLink(_)
  }

  def "addS3SpanPointer skips when eTag is null"() {
    given:
    def span = Mock(AgentSpan)

    when:
    SpanPointerUtils.addS3SpanPointer(span, "bucket", "key", null)

    then:
    0 * span.addLink(_)
  }

  def "addDynamoDbSpanPointer creates link with correct attributes"() {
    given:
    def links = []
    def span = Mock(AgentSpan)
    span.addLink(_) >> { AgentSpanLink link -> links.add(link) }

    when:
    SpanPointerUtils.addDynamoDbSpanPointer(span, "my-table", "id", "12345", null, null)

    then:
    links.size() == 1
    links[0].attributes().asMap().get("ptr.kind") == SpanPointerUtils.DYNAMODB_PTR_KIND
    links[0].attributes().asMap().get("ptr.dir") == SpanPointerUtils.DOWN_DIRECTION
    links[0].attributes().asMap().get("link.kind") == SpanPointerUtils.LINK_KIND
    links[0].attributes().asMap().get("ptr.hash") != null
  }

  def "addDynamoDbSpanPointer skips when tableName is null"() {
    given:
    def span = Mock(AgentSpan)

    when:
    SpanPointerUtils.addDynamoDbSpanPointer(span, null, "id", "12345", null, null)

    then:
    0 * span.addLink(_)
  }

  def "addDynamoDbSpanPointer skips when primaryKey1Name is null"() {
    given:
    def span = Mock(AgentSpan)

    when:
    SpanPointerUtils.addDynamoDbSpanPointer(span, "my-table", null, "12345", null, null)

    then:
    0 * span.addLink(_)
  }
}
