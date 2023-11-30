package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan


class ApplicationModuleTest extends IastModuleImplTestBase {

  private ApplicationModule module

  private IastRequestContext ctx

  def setup() {
    module = new ApplicationModuleImpl(dependencies)
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    final span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
  }

  void 'if realPath is null do nothing'() {

    when:
    module.onRealPath(null)

    then:
    0 * reporter._
  }

  void 'check vulnerabilities'() {
    given:
    final file  = ClassLoader.getSystemResource(path)
    final realPath = file.path

    when:
    module.onRealPath(realPath)

    then:
    if (expectedVulnType != null) {
      1 * reporter.report(_, _) >> { assertEvidence(it[1], expectedVulnType, expectedEvidence) }
    } else {
      0 * reporter._
    }

    where:
    path | expectedVulnType | expectedEvidence
    'application/insecurejsplayout/secure' | null | null
    'application/insecurejsplayout/insecure' | VulnerabilityType.INSECURE_JSP_LAYOUT |  ['/nestedinsecure/insecure2.jsp', '/insecure.jsp'] as String []
    'application/verbtampering/secure' | null | null
    'application/verbtampering/insecure' | VulnerabilityType.VERB_TAMPERING | 'http-method not defined in web.xml'
    'application/sessiontimeout/secure' | null | null
    'application/sessiontimeout/insecure' | VulnerabilityType.SESSION_TIMEOUT | 'Found vulnerable timeout value: 80'
    'application/directorylistingleak/secure' | null | null
    'application/directorylistingleak/insecure' | VulnerabilityType.DIRECTORY_LISTING_LEAK | 'Directory listings configured'
    'application/adminconsoleactive/secure' | null | null
    'application/adminconsoleactive/insecure' | VulnerabilityType.ADMIN_CONSOLE_ACTIVE | 'Tomcat Manager Application'
    'application/defaulthtmlescapeinvalid/secure' | null | null
    'application/defaulthtmlescapeinvalid/insecure' | VulnerabilityType.DEFAULT_HTML_ESCAPE_INVALID | 'defaultHtmlEscape tag should be set'
  }

  private static void assertVulnerability(final  vuln, final  expectedVulnType) {
    assert vuln != null
    assert vuln.getType() == expectedVulnType
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final  vuln, final  expectedVulnType, final  expectedEvidence) {
    assertVulnerability(vuln, expectedVulnType)
    final evidence = vuln.evidence
    assert evidence != null
    if(expectedEvidence instanceof String[]) {
      for (String s : expectedEvidence) {
        assert evidence.value.contains(s)
      }
    } else {
      assert evidence.value == expectedEvidence
    }
  }
}
