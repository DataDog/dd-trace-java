package com.datadog.iast

import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

import static com.datadog.iast.IastTag.Enabled.ANALYZED
import static com.datadog.iast.IastTag.Enabled.SKIPPED

class IastTagTest extends DDSpecification {

  void 'tags are sent on the segment (#tagName)'() {
    given:
    final segment = Mock(TraceSegment)

    when:
    tag.setTagTop(segment)

    then:
    1 * segment.setTagTop(tag.key(), tag.value())

    where:
    tagName    | tag
    "analyzed" | ANALYZED
    "skipped"  | SKIPPED
  }

  void 'tags dont fail with null segment (#tagName)'() {
    when:
    tag.setTagTop(null)

    then:
    noExceptionThrown()

    where:
    tagName    | tag
    "analyzed" | ANALYZED
    "skipped"  | SKIPPED
  }

  void 'tags are sent on the span (#tagName)'() {
    given:
    final span = Mock(AgentSpan)

    when:
    tag.setTag(span)

    then:
    1 * span.setTag(tag.key(), tag.value())

    where:
    tagName    | tag
    "analyzed" | ANALYZED
    "skipped"  | SKIPPED
  }

  void 'tags dont fail with null span (#tagName)'() {
    when:
    tag.setTag(null)

    then:
    noExceptionThrown()

    where:
    tagName    | tag
    "analyzed" | ANALYZED
    "skipped"  | SKIPPED
  }

  void 'enabled tags are not set if IAST is opt-out'() {
    given:
    injectSysConfig('iast.enabled', 'false')
    final span = Mock(AgentSpan)
    final segment = Mock(TraceSegment)
    final tag = IastTag.Enabled.withValue(1)

    when:
    tag.setTag(span)

    then:
    0 * _

    when:
    tag.setTagTop(segment)

    then:
    0 * _
  }
}
