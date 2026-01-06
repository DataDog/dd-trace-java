package datadog.trace.instrumentation.owasp.esapi

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestEncoderSuite
import org.owasp.esapi.Encoder
import org.owasp.esapi.codecs.Codec

class EncoderCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test #method propagation with mark #mark'() {
    given:
    final module = Mock(PropagationModule)
    final testSuite = new TestEncoderSuite(Mock(Encoder))
    InstrumentationBridge.registerIastModule(module)

    when:
    testSuite.&"$method".call(args)

    then:
    1 * module.taintStringIfTainted(_, _, false, mark)
    0 * module._

    where:
    method          | args                               | mark
    'encodeForHTML' | ['Ø-This is a quote']              | VulnerabilityMarks.XSS_MARK
    'canonicalize'  | ['Ø-This is a quote']              | VulnerabilityMarks.XSS_MARK
    'canonicalize'  | ['Ø-This is a quote', true]        | VulnerabilityMarks.XSS_MARK
    'canonicalize'  | ['Ø-This is a quote', true, true]  | VulnerabilityMarks.XSS_MARK
    'encodeForLDAP' | ['Ø-This is a quote']              | VulnerabilityMarks.LDAP_INJECTION_MARK
    'encodeForOS'   | [Mock(Codec), 'Ø-This is a quote'] | VulnerabilityMarks.COMMAND_INJECTION_MARK
    'encodeForSQL'  | [Mock(Codec), 'Ø-This is a quote'] | VulnerabilityMarks.SQL_INJECTION_MARK
  }
}
