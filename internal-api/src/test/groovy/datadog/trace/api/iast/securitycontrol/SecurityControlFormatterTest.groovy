package datadog.trace.api.iast.securitycontrol

import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.test.util.DDSpecification

class SecurityControlFormatterTest extends DDSpecification{

  void 'test happy path Input validator'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControl = result.get(0)
    securityControl.getType() == SecurityControlType.INPUT_VALIDATOR
    securityControl.getMarks() == VulnerabilityMarks.COMMAND_INJECTION_MARK
    securityControl.getClassName() == "bar.foo.CustomInputValidator"
    securityControl.getMethod() == "validate"
    securityControl.getParameterTypes() == null
    securityControl.getParametersToMark() == null

  }

  void 'test happy path sanitizer'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'SANITIZER:COMMAND_INJECTION:bar.foo.CustomSanitizer:sanitize'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControl = result.get(0)
    securityControl.getType() == SecurityControlType.SANITIZER
    securityControl.getMarks() == VulnerabilityMarks.COMMAND_INJECTION_MARK
    securityControl.getClassName() == "bar.foo.CustomSanitizer"
    securityControl.getMethod() == "sanitize"
    securityControl.getParameterTypes() == null
    securityControl.getParametersToMark() == null
  }


}
