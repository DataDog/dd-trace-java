package datadog.trace.agent.test.asserts

import datadog.trace.api.DDId
import datadog.trace.core.DDSpan
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.util.regex.Pattern

import static TagsAssert.assertTags
import static datadog.trace.agent.test.asserts.MetricsAssert.assertMetrics

class SpanAssert {
  private final DDSpan span
  private final checked = [:]

  private SpanAssert(span) {
    this.span = span
  }

  static void assertSpan(DDSpan span,
                         @ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.SpanAssert'])
                         @DelegatesTo(value = SpanAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def asserter = new SpanAssert(span)
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

  def spanType(String type) {
    assert span.spanType == type
    assert span.tags["span.type"] == null
    checked.spanType = true
  }

  def parent() {
    assert span.parentId == DDId.ZERO
    checked.parentId = true
  }

  def parentId(BigInteger parentId) {
    parentDDId(parentId != null ? DDId.from("$parentId") : null)
  }

  def parentDDId(DDId parentId) {
    assert span.parentId == parentId
    checked.parentId = true
  }

  def traceId(BigInteger traceId) {
    traceDDId(traceId != null ? DDId.from("$traceId") : null)
  }

  def traceDDId(DDId traceId) {
    assert span.traceId == traceId
    checked.traceId = true
  }

  def childOf(DDSpan parent) {
    parentDDId(parent.spanId)
    traceDDId(parent.traceId)
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

  void assertDefaults() {
    if (!checked.spanType) {
      spanType(null)
    }
    if (!checked.errored) {
      errored(false)
    }
    hasServiceName()
  }

  void tags(@ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.TagsAssert'])
            @DelegatesTo(value = TagsAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertTags(span, spec)
  }

  void metrics(@ClosureParams(value = SimpleType, options = ['datadog.trace.agent.test.asserts.MetricsAssert'])
               @DelegatesTo(value = MetricsAssert, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assertMetrics(span, spec)
  }
}
