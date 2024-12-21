package datadog.trace.bootstrap.instrumentation.spanpointers

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.SpanLink
import spock.lang.Specification

class SpanPointersHelperTest extends Specification {
  def "addSpanPointer adds correct link to span with basic values"() {
    given:
    AgentSpan span = Mock(AgentSpan)
    String kind = SpanPointersHelper.S3_PTR_KIND
    String[] components = ["some-bucket", "some-key.data", "ab12ef34"]
    String expectedHash = "e721375466d4116ab551213fdea08413"

    when:
    SpanPointersHelper.addSpanPointer(span, kind, components)

    then:
    1 * span.addLink({ SpanLink link ->
      assert link.traceId() == DDTraceId.ZERO
      assert link.spanId() == DDSpanId.ZERO
      assert link.attributes.asMap().get("ptr.kind") == kind
      assert link.attributes.asMap().get("ptr.dir") == SpanPointersHelper.DOWN_DIRECTION
      assert link.attributes.asMap().get("ptr.hash") == expectedHash
      assert link.attributes.asMap().get("link.kind") == SpanPointersHelper.LINK_KIND
      true
    })
  }

  def "addSpanPointer adds correct link to span with non-ascii key"() {
    given:
    AgentSpan span = Mock(AgentSpan)
    String kind = SpanPointersHelper.S3_PTR_KIND
    String[] components = ["some-bucket", "some-key.你好", "ab12ef34"]
    String expectedHash = "d1333a04b9928ab462b5c6cadfa401f4"

    when:
    SpanPointersHelper.addSpanPointer(span, kind, components)

    then:
    1 * span.addLink({ SpanLink link ->
      assert link.traceId() == DDTraceId.ZERO
      assert link.spanId() == DDSpanId.ZERO
      assert link.attributes.asMap().get("ptr.kind") == kind
      assert link.attributes.asMap().get("ptr.dir") == SpanPointersHelper.DOWN_DIRECTION
      assert link.attributes.asMap().get("ptr.hash") == expectedHash
      assert link.attributes.asMap().get("link.kind") == SpanPointersHelper.LINK_KIND
      true
    })
  }

  def "addSpanPointer adds correct link to span with multipart-upload"() {
    given:
    AgentSpan span = Mock(AgentSpan)
    String kind = SpanPointersHelper.S3_PTR_KIND
    String[] components = ["some-bucket", "some-key.data", "ab12ef34-5"]
    String expectedHash = "2b90dffc37ebc7bc610152c3dc72af9f"

    when:
    SpanPointersHelper.addSpanPointer(span, kind, components)

    then:
    1 * span.addLink({ SpanLink link ->
      assert link.traceId() == DDTraceId.ZERO
      assert link.spanId() == DDSpanId.ZERO
      assert link.attributes.asMap().get("ptr.kind") == kind
      assert link.attributes.asMap().get("ptr.dir") == SpanPointersHelper.DOWN_DIRECTION
      assert link.attributes.asMap().get("ptr.hash") == expectedHash
      assert link.attributes.asMap().get("link.kind") == SpanPointersHelper.LINK_KIND
      true
    })
  }
}
