package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.taint.Ranges
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class UnvalidatedRedirectModuleTest extends IastModuleImplTestBase {

  private UnvalidatedRedirectModule module

  def setup() {
    module = new UnvalidatedRedirectModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'iast module detects String redirect (#value)'(final String value, final String expected) {
    setup:
    final param = mapTainted(value)

    when:
    module.onRedirect(param)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value        | expected
    null         | null
    '/var'       | null
    '/==>var<==' | "/==>var<=="
  }

  void 'iast module detects URI redirect (#value)'(final URI value, final String expected) {
    setup:
    ctx.taintedObjects.taint(value, Ranges.forObject(new Source(SourceTypes.NONE, null, null)))

    when:
    module.onURIRedirect(value)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value                                | expected
    null                                 | null
    new URI("http://dummy.location.com") | true
  }

  void 'iast module detects String redirect with class and method (#value)'(final String value, final String expected) {
    setup:
    final param = mapTainted(value)
    final clazz = "class"
    final method = "method"

    when:
    module.onRedirect(param, clazz, method)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value        | expected
    null         | null
    '/var'       | null
    '/==>var<==' | "/==>var<=="
  }

  void 'if onHeader receives a Location header call onRedirect'() {
    setup:
    final urm = Spy(new UnvalidatedRedirectModuleImpl(dependencies))
    InstrumentationBridge.registerIastModule(urm)

    when:
    urm.onHeader(headerName, "value")

    then:
    expected * urm.onRedirect("value")

    where:
    headerName | expected
    "blah"     | 0
    "Location" | 1
    "location" | 1
  }

  void 'If all ranges from tainted element have referer header as source, is not an unvalidated redirect'() {
    setup:
    def value = 'test01'
    def refererSource = new Source(SourceTypes.REQUEST_HEADER_VALUE, 'referer', 'value')
    Range[] ranges = [new Range(0, 2, refererSource, NOT_MARKED), new Range(4, 1, refererSource, NOT_MARKED)]
    ctx.getTaintedObjects().taint(value, ranges)

    when:
    module.onRedirect(value)

    then:
    0 * reporter.report(_, _)
  }

  void 'If not all ranges from tainted element have referer header as source, is an unvalidated redirect'(final String value, final Range[] ranges) {
    setup:
    ctx.getTaintedObjects().taint(value, ranges)

    when:
    module.onRedirect(value)

    then:
    1 * reporter.report(_, _)

    where:
    value    | ranges
    'test01' | [
      new Range(0, 2, new Source(SourceTypes.REQUEST_HEADER_VALUE, 'referer', 'value'), NOT_MARKED),
      new Range(4, 1, new Source(SourceTypes.REQUEST_HEADER_VALUE, 'other', 'value'), NOT_MARKED)
    ]
    'test02' | [
      new Range(0, 2, new Source(SourceTypes.REQUEST_HEADER_VALUE, 'referer', 'value'), NOT_MARKED),
      new Range(4, 1, new Source(SourceTypes.REQUEST_PARAMETER_NAME, 'referer', 'value'), NOT_MARKED)
    ]
    'test03' | [
      new Range(0, 2, new Source(SourceTypes.REQUEST_HEADER_VALUE, null, null), NOT_MARKED),
      new Range(4, 1, new Source(SourceTypes.REQUEST_PARAMETER_NAME, 'referer', 'value'), NOT_MARKED)
    ]
  }

  void 'If all ranges from tainted element have unvalidated redirect mark vulnerability is not reported'() {
    given:
    final value = 'test'
    final Range[] ranges = [
      new Range(0, 2, new Source(SourceTypes.REQUEST_HEADER_VALUE, 'referer', 'value'), VulnerabilityMarks.UNVALIDATED_REDIRECT_MARK),
      new Range(4, 1, new Source(SourceTypes.REQUEST_PARAMETER_NAME, 'referer', 'value'), VulnerabilityMarks.UNVALIDATED_REDIRECT_MARK)
    ]
    ctx.getTaintedObjects().taint(value, ranges)

    when:
    module.onRedirect(value)

    then:
    0 * reporter.report(_, _)
  }


  private String mapTainted(final String value) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, NOT_MARKED)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.UNVALIDATED_REDIRECT
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
