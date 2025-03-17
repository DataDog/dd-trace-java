package datadog.trace.agent.test.asserts

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.regex.Pattern

import static datadog.trace.agent.test.asserts.LinksAssert.assertLinks
import static datadog.trace.agent.test.asserts.TagsAssert.assertTags

class SpanAssert {
  private final DDSpan span
  private final DDSpan previous
  private boolean checkLinks = true
  private final checked = [:]

  private SpanAssert(span, DDSpan previous) {
    this.span = span
    this.previous = previous
  }

  static void assertSpan(DDSpan span,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.SpanAssert'])
    @DelegatesTo(value = SpanAssert, strategy = Closure.DELEGATE_FIRST) Closure spec,
    DDSpan previous = null) {
    def asserter = new SpanAssert(span, previous)
    asserter.assertSpan spec
  }

  void assertSpan(
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.SpanAssert'])
    @DelegatesTo(value = SpanAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def clone = (Closure) spec.clone()
    clone.delegate = this
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(this)
    assertDefaults()
  }

  def assertSpanNameContains(String spanName, String... shouldContainArr) {
    for (String shouldContain : shouldContainArr) {
      assert spanName.toString().contains(shouldContain)
    }
  }

  def hasServiceName() {
    assert span.serviceName != null && !span.serviceName.isEmpty()
  }

  def serviceName(String name) {
    assert span.serviceName == name
    checked.serviceName = true
  }

  def operationName(String name) {
    assert span.operationName.toString() == name
    checked.operationName = true
  }

  def operationName(Closure<Boolean> eval) {
    assert eval(span.operationName.toString())
    checked.resourceName = true
  }

  def operationNameContains(String... operationNameParts) {
    assertSpanNameContains(span.operationName.toString(), operationNameParts)
    checked.operationName = true
  }

  def resourceName(Pattern pattern) {
    assert span.resourceName.toString().matches(pattern)
    checked.resourceName = true
  }

  def resourceName(String name) {
    assert span.resourceName.toString() == name
    checked.resourceName = true
  }

  def resourceName(Closure<Boolean> eval) {
    assert eval(span.resourceName.toString())
    checked.resourceName = true
  }

  def resourceNameContains(String... resourceNameParts) {
    assertSpanNameContains(span.resourceName.toString(), resourceNameParts)
    checked.resourceName = true
  }

  def duration(Closure<Boolean> eval) {
    assert eval(span.durationNano)
    checked.duration = true
  }

  def spanType(String type) {
    if (null == span.spanType) {
      // code less readable makes for a better assertion message, don't want NPE
      assert span.spanType == type
    } else {
      assert span.spanType.toString() == type
    }
    assert span.tags["span.type"] == null
    checked.spanType = true
  }

  def parent() {
    assert span.parentId == DDSpanId.ZERO
    checked.parentId = true
  }

  def parentSpanId(BigInteger parentId) {
    long id = parentId == null ? 0 : DDSpanId.from("$parentId")
    assert span.parentId == id
    checked.parentId = true
  }

  def traceId(BigInteger traceId) {
    traceDDId(traceId != null ? DDTraceId.from("$traceId") : null)
  }

  def traceDDId(DDTraceId traceId) {
    assert span.traceId == traceId
    checked.traceId = true
  }

  def childOf(DDSpan parent) {
    assert span.parentId == parent.spanId
    checked.parentId = true
    assert span.traceId == parent.traceId
    checked.traceId = true
  }

  def childOfPrevious() {
    assert previous != null
    childOf(previous)
  }

  def threadNameStartsWith(String threadName) {
    assert span.tags.get("thread.name")?.startsWith(threadName)
  }

  def notChildOf(DDSpan parent) {
    assert parent.spanId != span.parentId
    assert parent.traceId != span.traceId
  }

  def errored(boolean errored) {
    assert span.isError() == errored
    checked.errored = true
  }

  def topLevel(boolean topLevel) {
    assert span.isTopLevel() == topLevel
    checked.topLevel = true
  }

  def measured(boolean measured) {
    assert span.measured == measured
    checked.measured = true
  }

  void assertDefaults() {
    if (!checked.spanType) {
      spanType(null)
    }
    if (!checked.errored) {
      errored(false)
    }
    if (checkLinks) {
      if (!checked.links) {
        assert span.tags['_dd.span_links'] == null
      }
    }
    hasServiceName()
  }

  void tags(@ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TagsAssert'])
    @DelegatesTo(value = TagsAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertTags(span, spec)
  }

  void tags(boolean checkAllTags,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TagsAssert'])
    @DelegatesTo(value = TagsAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertTags(span, spec, checkAllTags)
  }

  void ignoreSpanLinks() {
    this.checkLinks = false
  }

  void links(@ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.LinksAssert'])
    @DelegatesTo(value = LinksAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    checked.links = true
    assertLinks(span, spec)
  }

  void links(boolean checkAllLinks,
    @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.LinksAssert'])
    @DelegatesTo(value = LinksAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    checked.links = true
    assertLinks(span, spec, checkAllLinks)
  }

  DDSpan getSpan() {
    return span
  }
}
