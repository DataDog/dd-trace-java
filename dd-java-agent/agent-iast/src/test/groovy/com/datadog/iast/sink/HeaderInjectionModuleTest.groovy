package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.HeaderInjectionModule

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static com.datadog.iast.util.HttpHeader.CONNECTION
import static com.datadog.iast.util.HttpHeader.LOCATION
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_ACCEPT
import static com.datadog.iast.util.HttpHeader.SEC_WEBSOCKET_LOCATION
import static com.datadog.iast.util.HttpHeader.UPGRADE
import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_NAME
import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_VALUE
import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_VALUE
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class HeaderInjectionModuleTest extends IastModuleImplTestBase {

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
    headerValue  | mark                                     | headerName   | expected
    '/==>var<==' | NOT_MARKED                               | 'headerName' | "headerName: /==>var<=="
    '/==>var<==' | VulnerabilityMarks.XPATH_INJECTION_MARK  | 'headerName' | "headerName: /==>var<=="
    'var'        | NOT_MARKED                               | 'headerName' | null
    '/==>var<==' | VulnerabilityMarks.HEADER_INJECTION_MARK | 'headerName' | null
  }

  void 'check excluded headers'() {
    when:
    module.onHeader(header.name, 'headerValue')

    then:
    0 * reporter.report(_, _ as Vulnerability)

    where:
    header << [SEC_WEBSOCKET_LOCATION, SEC_WEBSOCKET_ACCEPT, UPGRADE, CONNECTION, LOCATION]
  }

  void 'check exclusion for #header (excluded: #excluded)'() {
    given:
    final headerValue = 'headerValue'
    ctx.taintedObjects.taint(headerValue, sources as Range[])

    when:
    module.onHeader(header, headerValue)

    then:
    if (excluded) {
      0 * reporter._
    } else {
      1 * reporter.report(_, _) >> { it -> assertVulnerability(it[1] as Vulnerability, VulnerabilityType.HEADER_INJECTION) }
    }

    where:
    header                         | sources                                                   | excluded
    // exclude if sources coming only from headers
    'Access-Control-Allow-Origin'  | [requestParam()]                                          | false
    'Access-Control-Allow-Origin'  | [requestParam(), contentType()]                           | false
    'Access-Control-Allow-Origin'  | [contentType()]                                           | true

    // exclude if sources coming only from headers
    'Access-Control-Allow-Methods' | [requestParam()]                                          | false
    'Access-Control-Allow-Methods' | [requestParam(), contentType()]                           | false
    'Access-Control-Allow-Methods' | [contentType()]                                           | true

    // exclude if sources coming only from 'Cookie' headers
    'Set-Cookie'                   | [requestParam()]                                          | false
    'Set-Cookie'                   | [requestParam(), contentType()]                           | false
    'Set-Cookie'                   | [cookie()]                                                | true

    // exclude if reflected from header with the same name
    'Reflected'                    | contentType()                                             | false
    'Reflected'                    | [contentType(), requestHeader('Reflected', 'value')]      | false
    'Reflected'                    | [requestHeader('Reflected', 'value')]                     | true

    // exclude if reflected from 'Cache-Control' header
    'Pragma'                       | [contentType()]                                           | false
    'Pragma'                       | [contentType(), cacheControl()]                           | false
    'Pragma'                       | [cacheControl()]                                          | true

    // exclude if reflected from 'Accept-Encoding' header
    'Transfer-Encoding'            | [contentType()]                                           | false
    'Transfer-Encoding'            | [contentType(), acceptEncoding()]                         | false
    'Transfer-Encoding'            | [acceptEncoding()]                                        | true

    // exclude if reflected from 'Accept-Encoding' header
    'Content-Encoding'             | [contentType()]                                           | false
    'Content-Encoding'             | [contentType(), acceptEncoding()]                         | false
    'Content-Encoding'             | [acceptEncoding()]                                        | true

    // exclude if sources coming only from request header names
    'Vary'                         | [contentType()]                                           | false
    'Vary'                         | [contentType(), headerName('Accept')]                     | false
    'Vary'                         | [headerName('Content-Type'), headerName('Authorization')] | true
  }

  private static Range headerName(String name) {
    return source(REQUEST_HEADER_NAME, name, name)
  }

  private static Range cookie(String name = 'p', String value = 'value') {
    return source(REQUEST_HEADER_VALUE, 'Cookie', "$name=$value")
  }

  private static Range requestParam(String name = 'p', String value = 'value') {
    return source(REQUEST_PARAMETER_VALUE, name, value)
  }

  private static Range acceptEncoding(String value = 'gzip') {
    return requestHeader('Accept-Encoding', value)
  }

  private static Range cacheControl(String value = 'no-cache') {
    return requestHeader('Cache-Control', value)
  }

  private static Range contentType(String value = 'text/html') {
    return requestHeader('Content-Type', value)
  }

  private static Range requestHeader(String name, String value = 'text/html') {
    return source(REQUEST_HEADER_VALUE, name, value)
  }

  private static Range source(byte origin, String name, String value) {
    return new Range(0, value.length(), new Source(origin, name, value), NOT_MARKED)
  }

  private String mapTainted(final String value, final int mark) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, mark)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln, final VulnerabilityType type) {
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
