package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.propagation.PropagationModuleImpl
import datadog.trace.api.iast.SourceTypes

class EmailInjectionModuleTest extends IastModuleImplTestBase{

  private EmailInjectionModuleImpl module

  def setup() {
    module = new EmailInjectionModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  def "test onSendEmail with null messageContent"() {
    when:
    module.onSendEmail(null)

    then:
    noExceptionThrown()
  }

  def "test onSendEmail with non-null messageContent"() {
    given:
    def messageContent = "test message"
    def propagationModule = new PropagationModuleImpl()
    propagationModule.taintObject(messageContent, SourceTypes.NONE)

    when:
    module.onSendEmail(messageContent)

    then:
    1 * reporter.report(_, _) >> { args ->
      def vulnerability = args[1] as Vulnerability
      vulnerability.type == VulnerabilityType.EMAIL_HTML_INJECTION &&
        vulnerability.evidence == messageContent
    }
  }
}
