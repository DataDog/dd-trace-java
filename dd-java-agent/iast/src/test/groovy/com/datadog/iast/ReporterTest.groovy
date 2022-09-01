package com.datadog.iast

import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Location
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityBatch
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.TraceSegment
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class ReporterTest extends DDSpecification {

  void 'basic vulnerability reporting'() {
    given:
    final Reporter reporter = new Reporter()
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext()
    final reqCtx = Stub(RequestContext)
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    reqCtx.getTraceSegment() >> traceSegment
    VulnerabilityBatch batch = null

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx

    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forStack(new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    1 * traceSegment.setDataTop('iast', _) >> { batch = it[1] as VulnerabilityBatch }
    batch.toString() == '{"vulnerabilities":[{"evidence":{"value":"MD5"},"location":{"line":1,"path":"foo"},"type":"WEAK_HASH"}]}'
    0 * _
  }

  void 'two vulnerabilities'() {
    given:
    final Reporter reporter = new Reporter()
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext()
    final reqCtx = Stub(RequestContext)
    reqCtx.getData(RequestContextSlot.IAST) >> ctx
    reqCtx.getTraceSegment() >> traceSegment
    VulnerabilityBatch batch = null

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx

    final v1 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forStack(new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )
    final v2 = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forStack(new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD4")
      )

    when:
    reporter.report(span, v1)
    reporter.report(span, v2)

    then:
    1 * traceSegment.setDataTop('iast', _) >> { batch = it[1] as VulnerabilityBatch }
    batch.toString() == '{"vulnerabilities":[{"evidence":{"value":"MD5"},"location":{"line":1,"path":"foo"},"type":"WEAK_HASH"},{"evidence":{"value":"MD4"},"location":{"line":1,"path":"foo"},"type":"WEAK_HASH"}]}'
    0 * _
  }

  void 'null span does not throw'() {
    given:
    final Reporter reporter = new Reporter()
    final span = null
    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forStack(new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    noExceptionThrown()
    0 * _
  }

  void 'null RequestContext does not throw'() {
    given:
    final Reporter reporter = new Reporter()
    final span = Mock(AgentSpan)
    span.getRequestContext() >> null
    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forStack(new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    noExceptionThrown()
    1 * span.getRequestContext()
    0 * _
  }

  void 'null IastRequestContext does not throw'() {
    given:
    final Reporter reporter = new Reporter()
    final reqCtx = Mock(RequestContext)
    reqCtx.getData(RequestContextSlot.IAST) >> null
    final span = Mock(AgentSpan)
    span.getRequestContext() >> reqCtx
    final v = new Vulnerability(
      VulnerabilityType.WEAK_HASH,
      Location.forStack(new StackTraceElement("foo", "foo", "foo", 1)),
      new Evidence("MD5")
      )

    when:
    reporter.report(span, v)

    then:
    noExceptionThrown()
    1 * span.getRequestContext() >> reqCtx
    1 * reqCtx.getData(RequestContextSlot.IAST)
    0 * _
  }
}
