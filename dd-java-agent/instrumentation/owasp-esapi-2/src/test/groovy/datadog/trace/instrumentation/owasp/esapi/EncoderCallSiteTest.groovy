package datadog.trace.instrumentation.owasp.esapi

import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestEncoderSuite
import org.owasp.esapi.Encoder
import org.owasp.esapi.codecs.Codec

class EncoderCallSiteTest extends IastAgentTestRunner {

  void 'test #method propagation with mark #mark'() {
    given:
    final module = Mock(PropagationModule)
    final encoder = Stub(Encoder) {
      "$method"(_) >> {
        return it[0] + "Encoded"
      }
    }
    final testSuite = new TestEncoderSuite(encoder)
    InstrumentationBridge.registerIastModule(module)

    when:
    runUnderIastTrace { testSuite.&"$method".call(args) }

    then:
    1 * module.taintIfTainted(_ as IastContext, _, _, false, mark)
    0 * module._

    where:
    method          | args                               | mark
    'encodeForHTML' | ['Ø-This is a quote']              | VulnerabilityMarks.XSS_MARK
    'canonicalize'  | ['Ø-This is a quote']              | VulnerabilityMarks.XSS_MARK
    'canonicalize'  | ['Ø-This is a quote', true]        | VulnerabilityMarks.XSS_MARK
    'canonicalize'  | ['Ø-This is a quote', true, true]  | VulnerabilityMarks.XSS_MARK
    'encodeForLDAP' | ['Ø-This is a quote']              | VulnerabilityMarks.LDAP_INJECTION_MARK
    'encodeForOS'   | [Stub(Codec), 'Ø-This is a quote'] | VulnerabilityMarks.COMMAND_INJECTION_MARK
    'encodeForSQL'  | [Stub(Codec), 'Ø-This is a quote'] | VulnerabilityMarks.SQL_INJECTION_MARK
  }
}
