package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.XssModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.model.Range.NOT_MARKED
import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class XssModuleTest extends IastModuleImplTestBase {

  private XssModule module

  private List<Object> objectHolder

  private IastRequestContext ctx

  def setup() {
    module = registerDependencies(new XssModuleImpl())
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

  void 'module detects String XSS'() {
    setup:
    final param = mapTainted(s, mark)

    when:
    module.onXss(param as String)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    s            | mark                                  | expected
    null         | NOT_MARKED                            | null
    '/var'       | NOT_MARKED                            | null
    '/==>var<==' | NOT_MARKED                            | "/==>var<=="
    '/==>var<==' | VulnerabilityMarks.XSS_MARK           | null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK | "/==>var<=="
  }

  void 'module detects char[] XSS'() {
    setup:
    if (tainted) {
      ctx.taintedObjects.taintInputObject(buf, new Source(SourceTypes.NONE, '', ''), mark)
    }

    when:
    module.onXss(buf as char[])

    then:
    if (expected) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    buf                  | mark                                  | tainted | expected
    null                 | NOT_MARKED                            | false   | false
    'test'.toCharArray() | NOT_MARKED                            | true    | true
    'test'.toCharArray() | VulnerabilityMarks.XSS_MARK           | true    | false
    'test'.toCharArray() | VulnerabilityMarks.SQL_INJECTION_MARK | true    | true
  }

  void 'module detects String format and args [] XSS'() {
    setup:
    final param = mapTainted(format, mark)
    List<String> list = new ArrayList<>()
    for (String o : array) {
      list.add(mapTainted(o, mark))
    }


    when:
    module.onXss(param, list.toArray())

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    format       | array                  | mark                                  | expected
    null         | null                   | NOT_MARKED                            | null
    '/var'       | ['a', 'b']             | NOT_MARKED                            | null
    '/==>var<==' | ['a', 'b']             | NOT_MARKED                            | "/==>var<== a b"
    null         | ['a', 'b']             | NOT_MARKED                            | null
    '/var'       | ['==>a<==', null]      | NOT_MARKED                            | "/var ==>a<=="
    '/var'       | ['==>a<==', 'b']       | NOT_MARKED                            | "/var ==>a<== b"
    '/var'       | ['==>a<==', '==>b<=='] | NOT_MARKED                            | "/var ==>a<== ==>b<=="
    '/==>var<==' | ['==>a<==', '==>b<=='] | NOT_MARKED                            | "/==>var<== ==>a<== ==>b<=="
    '/==>var<==' | ['a', 'b']             | VulnerabilityMarks.XSS_MARK           | null
    '/==>var<==' | ['a', 'b']             | VulnerabilityMarks.SQL_INJECTION_MARK | "/==>var<== a b"
  }

  void 'iast module detects String xss with class and method (#value)'() {
    setup:
    final param = mapTainted(value, mark)
    final clazz = "class"
    final method = "method"

    when:
    module.onXss(param, clazz, method)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value        | mark| expected
    null         | NOT_MARKED| null
    '/var'       | NOT_MARKED| null
    '/==>var<==' | VulnerabilityMarks.XSS_MARK| null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK| "/==>var<=="
  }


  private String mapTainted(final String value, final int mark) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, mark)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.XSS
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
