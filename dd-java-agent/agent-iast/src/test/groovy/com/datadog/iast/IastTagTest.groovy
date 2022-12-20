package com.datadog.iast

import datadog.trace.api.TraceSegment
import datadog.trace.test.util.DDSpecification

import static com.datadog.iast.IastTag.REQUEST_ANALYZED
import static com.datadog.iast.IastTag.REQUEST_SKIPPED

class IastTagTest extends DDSpecification {

  void 'tags are sent on the segment'(final IastTag tag) {
    given:
    final segment = Mock(TraceSegment)

    when:
    tag.setTagTop(segment)

    then:
    1 * segment.setTagTop(tag.key(), tag.value())

    where:
    tag              | _
    REQUEST_ANALYZED | _
    REQUEST_SKIPPED  | _
  }

  void 'tags dont fail with null segment'(final IastTag tag) {
    when:
    tag.setTagTop(null)

    then:
    noExceptionThrown()

    where:
    tag              | _
    REQUEST_ANALYZED | _
    REQUEST_SKIPPED  | _
  }
}
