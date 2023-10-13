package com.datadog.iast

import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

import static com.datadog.iast.IastTag.ANALYZED
import static com.datadog.iast.IastTag.SKIPPED

class IastTagTest extends DDSpecification {

  void 'tags are sent on the segment'(final IastTag tag) {
    given:
    final segment = Mock(TraceSegment)

    when:
    tag.setTagTop(segment)

    then:
    1 * segment.setTagTop(tag.key(), tag.value())

    where:
    tag      | _
    ANALYZED | _
    SKIPPED  | _
  }

  void 'tags dont fail with null segment'(final IastTag tag) {
    when:
    tag.setTagTop(null)

    then:
    noExceptionThrown()

    where:
    tag      | _
    ANALYZED | _
    SKIPPED  | _
  }

  void 'tags are sent on the span'(final IastTag tag) {
    given:
    final span = Mock(AgentSpan)

    when:
    tag.setTag(span)

    then:
    1 * span.setTag(tag.key(), tag.value())

    where:
    tag      | _
    ANALYZED | _
    SKIPPED  | _
  }

  void 'tags dont fail with null span'(final IastTag tag) {
    when:
    tag.setTag(null)

    then:
    noExceptionThrown()

    where:
    tag      | _
    ANALYZED | _
    SKIPPED  | _
  }
}
