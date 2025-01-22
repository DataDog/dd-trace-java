package datadog.trace.core.tagprocessor

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.SpanLink
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class SpanPointersProcessorTest extends DDSpecification{
  def "SpanPointersProcessor adds correct link with basic values"() {
    given:
    def processor = new SpanPointersProcessor()
    def unsafeTags = [
      (InstrumentationTags.AWS_BUCKET_NAME): "some-bucket",
      (InstrumentationTags.AWS_OBJECT_KEY) : "some-key.data",
      "s3.eTag"                            : "ab12ef34"
    ]
    def spanContext = Mock(DDSpanContext)
    def spanLinks = []
    def expectedHash = "e721375466d4116ab551213fdea08413"

    when:
    // Process the tags; the processor should remove 's3.eTag' and add one link
    def returnedTags = processor.processTags(unsafeTags, spanContext, spanLinks)

    then:
    // 1. s3.eTag was removed
    !returnedTags.containsKey("s3.eTag")
    // 2. Exactly one link was added
    spanLinks.size() == 1
    // 3. Check link
    def link = spanLinks[0]
    link instanceof SpanLink
    link.traceId() == DDTraceId.ZERO
    link.spanId() == DDSpanId.ZERO
    link.attributes.asMap().get("ptr.kind") == SpanPointersProcessor.S3_PTR_KIND
    link.attributes.asMap().get("ptr.dir") == SpanPointersProcessor.DOWN_DIRECTION
    link.attributes.asMap().get("ptr.hash") == expectedHash
    link.attributes.asMap().get("link.kind") == SpanPointersProcessor.LINK_KIND
  }

  def "SpanPointersProcessor adds correct link with non-ascii key"() {
    given:
    def processor = new SpanPointersProcessor()
    def unsafeTags = [
      (InstrumentationTags.AWS_BUCKET_NAME): "some-bucket",
      (InstrumentationTags.AWS_OBJECT_KEY)  : "some-key.你好",
      "s3.eTag"                             : "ab12ef34"
    ]
    def spanContext = Mock(DDSpanContext)
    def spanLinks = []

    // From the original test, expected hash for these components
    def expectedHash = "d1333a04b9928ab462b5c6cadfa401f4"

    when:
    def returnedTags = processor.processTags(unsafeTags, spanContext, spanLinks)

    then:
    !returnedTags.containsKey("s3.eTag")
    spanLinks.size() == 1
    def link = spanLinks[0]
    link.traceId() == DDTraceId.ZERO
    link.spanId() == DDSpanId.ZERO
    link.attributes.asMap().get("ptr.kind") == SpanPointersProcessor.S3_PTR_KIND
    link.attributes.asMap().get("ptr.dir") == SpanPointersProcessor.DOWN_DIRECTION
    link.attributes.asMap().get("ptr.hash") == expectedHash
    link.attributes.asMap().get("link.kind") == SpanPointersProcessor.LINK_KIND
  }

  def "SpanPointersProcessor adds correct link with multipart-upload ETag"() {
    given:
    def processor = new SpanPointersProcessor()
    def unsafeTags = [
      (InstrumentationTags.AWS_BUCKET_NAME): "some-bucket",
      (InstrumentationTags.AWS_OBJECT_KEY)  : "some-key.data",
      "s3.eTag"                             : "ab12ef34-5"
    ]
    def spanContext = Mock(DDSpanContext)
    def spanLinks = []

    // From the original test, expected hash for these components
    def expectedHash = "2b90dffc37ebc7bc610152c3dc72af9f"

    when:
    def returnedTags = processor.processTags(unsafeTags, spanContext, spanLinks)

    then:
    !returnedTags.containsKey("s3.eTag")
    spanLinks.size() == 1
    def link = spanLinks[0]
    link.traceId() == DDTraceId.ZERO
    link.spanId() == DDSpanId.ZERO
    link.attributes.asMap().get("ptr.kind") == SpanPointersProcessor.S3_PTR_KIND
    link.attributes.asMap().get("ptr.dir") == SpanPointersProcessor.DOWN_DIRECTION
    link.attributes.asMap().get("ptr.hash") == expectedHash
    link.attributes.asMap().get("link.kind") == SpanPointersProcessor.LINK_KIND
  }
}
