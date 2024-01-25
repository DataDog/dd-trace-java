package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.HeaderInjectionModule

import static com.datadog.iast.taint.TaintUtils.addFromRangeList
import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static com.datadog.iast.util.HttpHeader.CONNECTION
import static com.datadog.iast.util.HttpHeader.LOCATION
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_ACCEPT
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_LOCATION
import static com.datadog.iast.util.HttpHeader.SET_COOKIE
import static com.datadog.iast.util.HttpHeader.UPGRADE
import static com.datadog.iast.util.HttpHeader.COOKIE
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class HeaderInjectionModuleTest extends IastModuleImplTestBase{

  private HeaderInjectionModule module

  def setup() {
    module = new HeaderInjectionModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'check header value injection'() {
    given:
    final taintedHeaderValue = mapTainted(headerValue, mark)

    when:
    module.onHeader(headerName, taintedHeaderValue)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    headerValue   | mark                                     | headerName               | expected
    '/==>var<=='  | NOT_MARKED                               | 'headerName'             | "headerName: /==>var<=="
    '/==>var<=='  | VulnerabilityMarks.XPATH_INJECTION_MARK | 'headerName' | "headerName: /==>var<=="
    'var'         | NOT_MARKED                                | 'headerName'             | null
    '/==>var<=='  | VulnerabilityMarks.HEADER_INJECTION_MARK  | 'headerName'             | null
  }

  void 'check excluded headers'() {
    when:
    module.onHeader(header.name, 'headerValue')

    then:
    0 * reporter.report(_, _ as Vulnerability)

    where:
    header << [SEC_WEBSOCKET_LOCATION, SEC_WEBSOCKET_ACCEPT, UPGRADE, CONNECTION, LOCATION]
  }

  void 'check set-cookie exclusion'(){
    given:
    final headerValue = 'headerValue'
    addFromRangeList(ctx.taintedObjects, 'headerValue', ranges)

    when:
    module.onHeader(SET_COOKIE.name, headerValue)

    then:
    expected * reporter.report(_, _ as Vulnerability)

    where:
    ranges | expected
    [[0, 2, COOKIE, 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]] | 0
    [
      [0, 2, COOKIE, 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
      [3, 4, COOKIE, 'sourceValue2', SourceTypes.REQUEST_HEADER_VALUE]
    ] | 0
    [
      [0, 2, COOKIE, 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
      [0, 2, 'sourceName', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]
    ] | 1
    [[0, 2, 'sourceName', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]] | 1
    [[0, 2, 'sourceName', 'sourceValue', SourceTypes.GRPC_BODY]] | 1
    [[0, 2, SET_COOKIE, 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]] | 1
  }

  void 'check reflected header exclusion'(){
    given:
    final headerName = 'headerName'
    final headerValue = 'headerValue'
    addFromRangeList(ctx.taintedObjects, 'headerValue', ranges)

    when:
    module.onHeader(headerName, headerValue)

    then:
    expected * reporter.report(_, _ as Vulnerability)

    where:
    ranges | expected
    [[0, 2, COOKIE, 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]] | 1
    [
      [0, 2, 'headerName', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
      [3, 4, 'headerName', 'sourceValue2', SourceTypes.REQUEST_HEADER_VALUE]
    ] | 1
    [[0, 2, 'headerName', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]] | 0
  }

  void 'check access-control-allow-* exclusion'(){
    given:
    final headerValue = 'headerValue'
    addFromRangeList(ctx.taintedObjects, 'headerValue', suite.ranges)

    when:
    module.onHeader(suite.header, headerValue)

    then:
    suite.expected * reporter.report(_, _ as Vulnerability)

    where:
    suite << createTestSuite()
  }

  private Iterable<TestSuite> createTestSuite() {
    final result = []
    final headerNames = ['Access-Control-Allow-Origin', 'access-control-allow-origin', 'ACCESS-CONTROL-ALLOW-METHODS']
    for (headerName in headerNames){
      result.add(createTestSuite(headerName, [[0, 2, 'sourceName', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]], 0))
      result.add(createTestSuite(headerName, [
        [0, 2, 'sourceName', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
        [3, 4, 'sourceName2', 'sourceValue2', SourceTypes.REQUEST_HEADER_VALUE]
      ], 0))
      result.add(createTestSuite(headerName, [[0, 2, 'sourceName', 'sourceValue', SourceTypes.GRPC_BODY]], 1))
      result.add(createTestSuite(headerName, [
        [0, 2, 'sourceName', 'sourceValue', SourceTypes.GRPC_BODY],
        [3, 4, 'sourceName2', 'sourceValue2', SourceTypes.REQUEST_HEADER_VALUE]
      ], 1))
    }
    return result as Iterable<TestSuite>
  }

  private createTestSuite(header, ranges, expected) {
    return new TestSuite('header': header as String, 'ranges': ranges as List<List<Object>>, 'expected': expected as Integer)
  }

  private static class TestSuite {
    String header
    List<List<Object>> ranges
    Integer expected
  }

  private String mapTainted(final String value, final int mark) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, mark)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln, final VulnerabilityType type ) {
    assert vuln != null
    assert vuln.getType() == type
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final Vulnerability vuln, final String expected, final VulnerabilityType type = VulnerabilityType.HEADER_INJECTION) {
    assertVulnerability(vuln, type)
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
  }
}
