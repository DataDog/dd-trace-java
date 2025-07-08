package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.taint.Ranges
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.XssModule

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class XssModuleTest extends IastModuleImplTestBase {

  private XssModule module

  def setup() {
    module = new XssModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
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
      ctx.taintedObjects.taint(buf, Ranges.forObject(new Source(SourceTypes.NONE, '', ''), mark))
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

  void 'module detects Charsequence XSS with file and line'() {
    setup:
    final param = mapTainted(s, mark)

    when:
    module.onXss(param as CharSequence, file as String, line as int)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    s     | file | line        | mark                                  | expected
    null    | 'test' | 3      | NOT_MARKED                            | null
    '/var'    | 'test' | 3    | NOT_MARKED                            | null
    '/==>var<=='| 'test' | 3  | NOT_MARKED                            | "/==>var<=="
    '/==>var<=='| 'test' | 3  | VulnerabilityMarks.XSS_MARK           | null
    '/==>var<=='| 'test' | 3  | VulnerabilityMarks.SQL_INJECTION_MARK | "/==>var<=="
    '/==>var<=='| null | 3  | VulnerabilityMarks.SQL_INJECTION_MARK | null
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

  void 'class and method names are truncated when exceeding max length'() {
    setup:
    final param = mapTainted('/==>value<==', NOT_MARKED)
    final clazz = 'c' * 600
    final method = 'm' * 600

    when:
    module.onXss(param, clazz, method)

    then:
    1 * reporter.report(_, _) >> { args ->
      final vuln = args[1] as Vulnerability
      assertEvidence(vuln, '/==>value<==')
      assert vuln.location.path.length() == 500
      assert vuln.location.method.length() == 500
      assert vuln.location.path == clazz.substring(0, 500)
      assert vuln.location.method == method.substring(0, 500)
    }
  }

  void 'file name is truncated when exceeding max length'() {
    setup:
    final param = mapTainted('/==>value<==', NOT_MARKED)
    final file = 'f' * 600
    final line = 42

    when:
    module.onXss(param as CharSequence, file, line)

    then:
    1 * reporter.report(_, _) >> { args ->
      final vuln = args[1] as Vulnerability
      assertEvidence(vuln, '/==>value<==')
      assert vuln.location.path.length() == 500
      assert vuln.location.line == line
      assert vuln.location.path == file.substring(0, 500)
    }
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
