package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.XPathInjectionModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.model.Range.NOT_MARKED
import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class XPathInjectionModuleTest extends IastModuleImplTestBase {

  private XPathInjectionModule module

  private List<Object> objectHolder

  private IastRequestContext ctx

  def setup() {
    module = registerDependencies(new XPathInjectionModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    final span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
    overheadController.consumeQuota(_, _) >> true
  }

  void 'module detects String expression'(final String expression, final int mark, final String expected) {
    setup:
    final param = mapTainted(expression, mark)

    when:
    module.onExpression(param)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    expression   | mark                                    | expected
    null         | NOT_MARKED                              | null
    '/var'       | NOT_MARKED                              | null
    '/==>var<==' | NOT_MARKED                              | "/==>var<=="
    '/==>var<==' | VulnerabilityMarks.XPATH_INJECTION_MARK | null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK   | "/==>var<=="
  }


  private String mapTainted(final String value, final int mark) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, mark)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.XPATH_INJECTION
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final Vulnerability vuln, final String expected) {
    assertVulnerability(vuln)
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
  }
}
