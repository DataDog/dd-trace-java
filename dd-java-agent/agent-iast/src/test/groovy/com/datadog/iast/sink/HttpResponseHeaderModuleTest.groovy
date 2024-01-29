package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.overhead.Operations
import com.datadog.iast.taint.TaintedObjects
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.util.Cookie
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.addFromRangeList
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class HttpResponseHeaderModuleTest extends IastModuleImplTestBase {

  private HttpResponseHeaderModuleImpl module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new HttpResponseHeaderModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
    InstrumentationBridge.registerIastModule(new InsecureCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoHttpOnlyCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new NoSameSiteCookieModuleImpl())
    InstrumentationBridge.registerIastModule(new HstsMissingHeaderModuleImpl(dependencies))
    InstrumentationBridge.registerIastModule(new UnvalidatedRedirectModuleImpl(dependencies))
    InstrumentationBridge.registerIastModule(new HeaderInjectionModuleImpl(dependencies))
  }

  @Override
  protected AgentTracer.TracerAPI buildAgentTracer() {
    return Mock(AgentTracer.TracerAPI) {
      activeSpan() >> span
      getTraceSegment() >> traceSegment
    }
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'check quota is consumed correctly'() {
    given:
    Map<VulnerabilityType, Vulnerability> savedVul = [:]
    final onReport = { Vulnerability vul ->
      savedVul.put(vul.type, vul)
    }
    final cookie = Cookie.named('user-id').build()

    when:
    module.onCookie(cookie)

    then:
    1 * tracer.activeSpan() >> span
    1 * reporter.report(_, _ as Vulnerability) >> { onReport.call(it[1] as Vulnerability) }
    1 * reporter.report(_, _ as Vulnerability) >> { onReport.call(it[1] as Vulnerability) }
    1 * reporter.report(_, _ as Vulnerability) >> { onReport.call(it[1] as Vulnerability) }
    0 * _
    with(savedVul[VulnerabilityType.INSECURE_COOKIE]) {
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
    with(savedVul[VulnerabilityType.NO_HTTPONLY_COOKIE]) {
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
    with(savedVul[VulnerabilityType.NO_SAMESITE_COOKIE]) {
      type == VulnerabilityType.NO_SAMESITE_COOKIE
      location != null
      with(evidence) {
        value == cookie.cookieName
      }
    }
  }

  void 'exercise onHeader'() {
    when:
    module.onHeader(header, value)

    then:
    overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span) >> false // do not report in this test
    activeSpanCount * tracer.activeSpan() >>  {
      return span
    }
    0 * _

    where:
    header                      | value             | activeSpanCount
    "Set-Cookie"                | "user-id=7"       | 3
    "X-Content-Type-Options"    | "nosniff"         | 2
    "Content-Type"              | "text/html"       | 2
    "Strict-Transport-Security" | "invalid max age" | 2
  }

  void 'exercise IastRequestController'(){
    given:
    final taintedObjects = Stub(TaintedObjects)
    IastRequestContext ctx = new IastRequestContext(taintedObjects)

    when:
    ctx.setxForwardedProto('https')

    then:
    ctx.getxForwardedProto() == 'https'
  }

  void 'exercise IastRequestContext'(){
    given:
    final taintedObjects = Stub(TaintedObjects)

    when:
    IastRequestContext ctx = new IastRequestContext(taintedObjects)
    ctx.setxForwardedProto('https')
    ctx.setContentType("text/html")
    ctx.setxContentTypeOptions('nosniff')
    ctx.getxContentTypeOptions()
    ctx.setStrictTransportSecurity('max-age=2345')

    then:
    0 * _
  }

  void 'check header value injection'(final String headerName, final int mark, final String headerValue, final String expected) {
    given:
    Vulnerability savedVul
    final taintedHeaderValue = mapTainted(headerValue, mark)

    when:
    module.onHeader(headerName, taintedHeaderValue)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertEvidence(savedVul, expected, VulnerabilityType.HEADER_INJECTION)

    where:
    headerValue   | mark                                     | headerName               | expected
    '/==>var<=='  | NOT_MARKED                               | 'headerName'             | "headerName: /==>var<=="
    '/==>var<=='  | VulnerabilityMarks.XPATH_INJECTION_MARK  | 'headerName'             | "headerName: /==>var<=="
  }

  void 'check untainted header value injection'(final String headerName, final int mark, final String headerValue, final String expected) {
    given:
    final taintedHeaderValue = mapTainted(headerValue, mark)

    when:
    module.onHeader(headerName, taintedHeaderValue)

    then:
    0 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }

    where:
    headerValue   | mark                                      | headerName               | expected
    'var'         | NOT_MARKED                                | 'headerName'             | null
    '/==>var<=='  | VulnerabilityMarks.HEADER_INJECTION_MARK  | 'headerName'             | null
  }


  void 'check unvalidated redirect exclusion'(final String headerName, final int mark, final String headerValue, final String expected) {
    given:
    Vulnerability savedVul
    final taintedHeaderValue = mapTainted(headerValue, mark)

    when:
    module.onHeader(headerName, taintedHeaderValue)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertEvidence(savedVul, expected, VulnerabilityType.UNVALIDATED_REDIRECT)

    where:
    headerValue   | mark                                     | headerName               | expected
    '/==>var<=='  | NOT_MARKED                               | 'location'               | "/==>var<=="
  }

  void 'check header exclusions'(final String headerName, final int mark, final String headerValue) {
    given:
    final taintedHeaderValue = mapTainted(headerValue, mark)

    when:
    module.onHeader(headerName, taintedHeaderValue)

    then:
    0 * reporter.report(_, _ as Vulnerability)

    where:
    headerValue    | mark                                    | headerName
    '/==>var<=='  | NOT_MARKED                               | 'Sec-WebSocket-Location'
    '/==>var<=='  | NOT_MARKED                               | 'Sec-WebSocket-Accept'
    '/==>var<=='  | NOT_MARKED                               | 'Upgrade'
    '/==>var<=='  | NOT_MARKED                               | 'Connection'
  }

  void 'range test'(){
    given:
    addFromRangeList(ctx.taintedObjects, headerValue, ranges)
    ignoreCookieVulnerabilities() // TODO this test is broken (it should only test header injection)

    when:
    module.onHeader(headerName, headerValue)

    then:
    0 * reporter.report(_, _ as Vulnerability)

    where:
    headerValue | headerName                    | ranges
    'pepito'    | 'Sec-WebSocket-Location'      | [[0, 2, 'sourceName', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]]
    'pepito'    | 'Access-Control-Allow-Origin' | [[0, 2, 'origin', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]]
    'pepito'    | 'Access-Control-Allow-Origin' | [
      [0, 2, 'origin', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
      [2, 2, 'origin', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]
    ]
    'pepito'    | 'Set-Cookie'                  | [[0, 2, 'Set-Cookie', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]]
    'pepito'    | 'Set-Cookie'                  | [
      [0, 2, 'Set-Cookie', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
      [2, 2, 'Set-Cookie', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]
    ]
  }

  void 'range test exclusions'(){
    given:
    Vulnerability savedVul
    addFromRangeList(ctx.taintedObjects, headerValue, ranges)
    ignoreCookieVulnerabilities() // TODO this test is broken (it should only test header injection)

    when:
    module.onHeader(headerName, headerValue)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertEvidence(savedVul, expected, VulnerabilityType.HEADER_INJECTION)

    where:
    headerValue | expected                                           | headerName                   | ranges
    'pepito'    | 'Access-Control-Allow-Origin: ==>pe<==pito'        |'Access-Control-Allow-Origin' | [[0, 2, 'X-Test-Header', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]]
    'pepito'    | 'Access-Control-Allow-Origin: ==>pe<====>pi<==to'  |'Access-Control-Allow-Origin' | [
      [0, 2, 'origin', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
      [2, 2, 'X-Test-Header', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]
    ]
    'pepito'    | 'Set-Cookie: ==>pe<==pito'                        |'Set-Cookie'                  | [[0, 2, 'X-Test-Header', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]]
    'pepito'    | 'Set-Cookie: ==>pe<====>pi<==to'                  |'Set-Cookie'                  | [
      [0, 2, 'Set-Cookie', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE],
      [2, 2, 'X-Test-Header', 'sourceValue', SourceTypes.REQUEST_HEADER_VALUE]
    ]
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

  private void ignoreCookieVulnerabilities() {
    InstrumentationBridge.registerIastModule(Stub(InsecureCookieModuleImpl) {
      isVulnerable(_ as Cookie) >> false
    })
    InstrumentationBridge.registerIastModule(Stub(NoHttpOnlyCookieModuleImpl){
      isVulnerable(_ as Cookie) >> false
    })
    InstrumentationBridge.registerIastModule(Stub(NoSameSiteCookieModuleImpl){
      isVulnerable(_ as Cookie) >> false
    })
  }
}
