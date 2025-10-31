package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import java.nio.file.FileVisitResult
import java.nio.file.Paths

import static com.datadog.iast.model.VulnerabilityType.ADMIN_CONSOLE_ACTIVE
import static com.datadog.iast.model.VulnerabilityType.DEFAULT_HTML_ESCAPE_INVALID
import static com.datadog.iast.model.VulnerabilityType.DEFAULT_APP_DEPLOYED
import static com.datadog.iast.model.VulnerabilityType.DIRECTORY_LISTING_LEAK
import static com.datadog.iast.model.VulnerabilityType.INSECURE_JSP_LAYOUT
import static com.datadog.iast.model.VulnerabilityType.SESSION_TIMEOUT
import static com.datadog.iast.model.VulnerabilityType.VERB_TAMPERING
import static com.datadog.iast.sink.ApplicationModuleImpl.SESSION_REWRITING_EVIDENCE_VALUE

class ApplicationModuleTest extends IastModuleImplTestBase {

  private static final int NO_LINE = -1

  private ApplicationModule module

  def setup() {
    module = new ApplicationModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'if realPath is null do nothing'() {

    when:
    module.onRealPath(null)

    then:
    0 * reporter._
  }

  void 'check vulnerabilities #path'() {
    given:
    final file = ClassLoader.getSystemResource(path)
    final realPath = file.path

    when:
    module.onRealPath(realPath)

    then:
    if (expectedVulnType != null) {
      1 * reporter.report(_, _) >> { assertEvidence(it[1], expectedVulnType, expectedEvidence, line) }
    } else {
      0 * reporter._
    }

    where:
    path                                              | expectedVulnType            | expectedEvidence                                           | line
    'application/insecurejsplayout/secure'            | null                        | null                                                       | _
    'application/insecurejsplayout/insecure'          | INSECURE_JSP_LAYOUT         | ['/nestedinsecure', '/nestedinsecure/nestedinsecure', '/'] | NO_LINE
    'application/verbtampering/secure'                | null                        | null                                                       | _
    'application/verbtampering/insecure'              | VERB_TAMPERING              | 'http-method not defined in web.xml'                       | 6
    'application/sessiontimeout/secure'               | null                        | null                                                       | _
    'application/sessiontimeout/insecure'             | SESSION_TIMEOUT             | 'Found vulnerable timeout value: 80'                       | 7
    'application/directorylistingleak/secure'         | null                        | null                                                       | _
    'application/directorylistingleak/insecure/tomcat'| DIRECTORY_LISTING_LEAK      | 'Directory listings configured'                            | 14
    'application/directorylistingleak/insecure/weblogic'     | DIRECTORY_LISTING_LEAK      | 'Directory listings configured'                             | 17
    'application/directorylistingleak/insecure/websphere/xmi'        | DIRECTORY_LISTING_LEAK      | 'Directory listings configured'                             | 1
    'application/directorylistingleak/insecure/websphere/xml'        | DIRECTORY_LISTING_LEAK      | 'Directory listings configured'                             | 10
    'application/adminconsoleactive/secure'           | null                        | null                                                       | _
    'application/adminconsoleactive/insecure/tomcat/manager' | ADMIN_CONSOLE_ACTIVE | ApplicationModuleImpl.TOMCAT_MANAGER_APP                   | NO_LINE
    'application/adminconsoleactive/insecure/tomcat/host'    | ADMIN_CONSOLE_ACTIVE | ApplicationModuleImpl.TOMCAT_HOST_MANAGER_APP              | NO_LINE
    'application/defaultappdeployed/secure'           | null                        | null                                                       | _
    'application/defaultappdeployed/insecure/tomcat/samples'         | DEFAULT_APP_DEPLOYED        | ApplicationModuleImpl.TOMCAT_SAMPLES_APP                    | NO_LINE
    'application/defaultappdeployed/insecure/jetty/async'            | DEFAULT_APP_DEPLOYED        | ApplicationModuleImpl.JETTY_ASYNC_REST_APP                  | NO_LINE
    'application/defaultappdeployed/insecure/jetty/jaas'             | DEFAULT_APP_DEPLOYED        | ApplicationModuleImpl.JETTY_JAAS_APP                        | NO_LINE
    'application/defaultappdeployed/insecure/jetty/javadoc'          | DEFAULT_APP_DEPLOYED        | ApplicationModuleImpl.JETTY_JAVADOC_APP                     | NO_LINE
    'application/defaultappdeployed/insecure/jetty/jndi'             | DEFAULT_APP_DEPLOYED        | ApplicationModuleImpl.JETTY_JNDI_APP                        | NO_LINE
    'application/defaultappdeployed/insecure/jetty/spec'             | DEFAULT_APP_DEPLOYED        | ApplicationModuleImpl.JETTY_SPEC_APP                        | NO_LINE
    'application/defaultappdeployed/insecure/jetty/test'             | DEFAULT_APP_DEPLOYED        | ApplicationModuleImpl.JETTY_TEST_APP                        | NO_LINE
    'application/defaulthtmlescapeinvalid/secure'     | null                        | null                                                       | _
    'application/defaulthtmlescapeinvalid/secure_tag' | null                        | null                                                       | _
    'application/defaulthtmlescapeinvalid/false_tag'  | DEFAULT_HTML_ESCAPE_INVALID | 'defaultHtmlEscape tag should be true'                     | 8
    'application/defaulthtmlescapeinvalid/no_tag_1'   | DEFAULT_HTML_ESCAPE_INVALID | 'defaultHtmlEscape tag should be set'                      | NO_LINE
    'application/defaulthtmlescapeinvalid/no_tag_2'   | DEFAULT_HTML_ESCAPE_INVALID | 'defaultHtmlEscape tag should be set'                      | NO_LINE
  }

  void 'iast module detects session rewriting on sessionTrackingModes'() {
    when:
    module.checkSessionTrackingModes(sessionTrackingModes as Set<String>)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertSessionRewriting(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }
    0 * reporter.report(_, _)

    where:
    sessionTrackingModes        | expected
    []                          | null
    ['COOKIE']                  | null
    ['URL']                     | SESSION_REWRITING_EVIDENCE_VALUE
    ['COOKIE', 'URL']           | SESSION_REWRITING_EVIDENCE_VALUE
  }

  private static void assertSessionRewriting(final Vulnerability vuln, final String expected) {
    assertVulnerability(vuln, VulnerabilityType.SESSION_REWRITING)
    final evidence = vuln.getEvidence()
    assert evidence.value == expected
  }

  private static void assertVulnerability(final vuln, final expectedVulnType) {
    assert vuln != null
    assert vuln.getType() == expectedVulnType
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final vuln, final expectedVulnType, final expectedEvidence, final line) {
    assertVulnerability(vuln, expectedVulnType)
    final evidence = vuln.evidence
    assert evidence != null
    if (expectedEvidence instanceof Collection) {
      if (expectedVulnType == INSECURE_JSP_LAYOUT) {
        // some of the nested paths can be dropped by the file visitor
        final parts = (evidence.value as String).split('\n')*.trim()
        assert expectedEvidence.any { parts.contains(it) }
      } else {
        expectedEvidence.each {
          assert evidence.value.contains(it)
        }
      }
    } else {
      assert evidence.value == expectedEvidence
    }
    assert vuln.location.line == line
  }

  void 'insecure jsp visitor handles root directory without name'() {
    given:
    def visitorClass = ApplicationModuleImpl.declaredClasses.find { it.simpleName == 'InsecureJspFolderVisitor' }
    def constructor = visitorClass.getDeclaredConstructor()
    constructor.accessible = true
    def visitor = constructor.newInstance()

    when:
    def result = visitor.preVisitDirectory(Paths.get(File.separator), null)

    then:
    result == FileVisitResult.CONTINUE
  }
}
