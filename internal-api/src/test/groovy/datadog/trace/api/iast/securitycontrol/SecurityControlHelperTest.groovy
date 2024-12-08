package datadog.trace.api.iast.securitycontrol

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.test.util.DDSpecification

class SecurityControlHelperTest extends DDSpecification {


  void 'test no module'(){
    setup:
    final toValidate = 'test'
    final marks = VulnerabilityMarks.XSS_MARK

    when:
    SecurityControlHelper.setSecureMarks(toValidate, marks)

    then:
    0 * _
  }

  void 'test'(){
    setup:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final toValidate = 'test'
    final marks = VulnerabilityMarks.XSS_MARK

    when:
    SecurityControlHelper.setSecureMarks(toValidate, marks)

    then:
    1 * iastModule.markIfTainted(toValidate, marks)
    0 * _
  }
}
