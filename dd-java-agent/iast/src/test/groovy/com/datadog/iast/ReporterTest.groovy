package com.datadog.iast

import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.TraceSegment
import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

class ReporterTest extends DDSpecification {

  void 'basic vulnerability reporting'() {
    given:
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext()
    final reqCtx = Stub(RequestContext)
    reqCtx.getIastContext() >> ctx
    reqCtx.getTraceSegment() >> traceSegment

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx

    final v = Vulnerability.builder()
      .type(VulnerabilityType.WEAK_HASH)
      .evidence(new Evidence("md5"))
      .build()

    when:
    Reporter.report(span, v)

    then:
    ctx.getVulnerabilities() == [v]

    when:
    Reporter.finalizeReports(reqCtx)

    then:
    1 * traceSegment.setTagTop('_dd.iast.json', '{"vulnerabilities":[{"evidence":{"value":"md5"},"type":"WEAK_HASH"}]}')
  }

  void 'two vulnerabilities'() {
    given:
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext()
    final reqCtx = Stub(RequestContext)
    reqCtx.getIastContext() >> ctx
    reqCtx.getTraceSegment() >> traceSegment

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx

    final v1 = Vulnerability.builder()
      .type(VulnerabilityType.WEAK_HASH)
      .evidence(new Evidence("md5"))
      .build()
    final v2 = Vulnerability.builder()
      .type(VulnerabilityType.WEAK_HASH)
      .evidence(new Evidence("md4"))
      .build()

    when:
    Reporter.report(span, v1)

    then:
    ctx.getVulnerabilities() == [v1]

    when:
    Reporter.report(span, v2)


    then:
    ctx.getVulnerabilities() == [v1, v2]

    when:
    Reporter.finalizeReports(reqCtx)

    then:
    1 * traceSegment.setTagTop('_dd.iast.json', '{"vulnerabilities":[{"evidence":{"value":"md5"},"type":"WEAK_HASH"},{"evidence":{"value":"md4"},"type":"WEAK_HASH"}]}')
  }

  void 'vulnerability reporting with stack'() {
    given:
    final traceSegment = Mock(TraceSegment)
    final ctx = new IastRequestContext()
    final reqCtx = Stub(RequestContext)
    reqCtx.getIastContext() >> ctx
    reqCtx.getTraceSegment() >> traceSegment

    final span = Stub(AgentSpan)
    span.getRequestContext() >> reqCtx

    final builder = Vulnerability.builder()
      .type(VulnerabilityType.WEAK_HASH)
      .evidence(new Evidence("md5"))
    AnyStackRunner.callWithinStack("test.clazz", Vulnerability.BuilderMore.getMethod("computeLocation"), builder)
    final v = builder.build()

    when:
    Reporter.report(span, v)

    then:
    ctx.getVulnerabilities() == [v]

    when:
    Reporter.finalizeReports(reqCtx)

    then:
    1 * traceSegment.setTagTop("_dd.iast.json", '{"vulnerabilities":[{"evidence":{"value":"md5"},"location":{"line":-1,"path":"test.clazz"},"type":"WEAK_HASH"}]}')
  }

  void 'null span does not throw'() {
    given:
    final span = null
    final v = Vulnerability.builder()
      .type(VulnerabilityType.WEAK_HASH)
      .evidence(new Evidence("md5"))
      .build()

    when:
    Reporter.report(span, v)

    then:
    noExceptionThrown()
  }

  void 'null RequestContext does not throw'() {
    given:
    final span = Stub(AgentSpan)
    span.getRequestContext() >> null
    final v = Vulnerability.builder()
      .type(VulnerabilityType.WEAK_HASH)
      .evidence(new Evidence("md5"))
      .build()

    when:
    Reporter.report(span, v)

    then:
    noExceptionThrown()
  }
}
