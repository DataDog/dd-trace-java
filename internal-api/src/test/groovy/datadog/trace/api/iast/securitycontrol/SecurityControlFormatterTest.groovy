package datadog.trace.api.iast.securitycontrol

import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.test.util.DDSpecification

class SecurityControlFormatterTest extends DDSpecification{

  void 'test simple Input validator'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControls = result.get('bar/foo/CustomInputValidator')
    securityControls.size() == 1
    def securityControl = securityControls.get(0)
    securityControl.getType() == SecurityControlType.INPUT_VALIDATOR
    securityControl.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    securityControl.getClassName() == "bar/foo/CustomInputValidator"
    securityControl.getMethod() == "validate"
    securityControl.getParameterTypes() == null
    securityControl.getParametersToMark() == null
  }

  void 'test simple sanitizer'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'SANITIZER:COMMAND_INJECTION:bar.foo.CustomSanitizer:sanitize'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControls = result.get('bar/foo/CustomSanitizer')
    securityControls.size() == 1
    def securityControl = securityControls.get(0)
    securityControl.getType() == SecurityControlType.SANITIZER
    securityControl.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    securityControl.getClassName() == "bar/foo/CustomSanitizer"
    securityControl.getMethod() == "sanitize"
    securityControl.getParameterTypes() == null
    securityControl.getParametersToMark() == null
  }

  void 'test multiple security controls'(){
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate;SANITIZER:COMMAND_INJECTION:bar.foo.CustomSanitizer:sanitize'
    final result = formatter.format(config)

    expect:
    result.size() == 2
    def inputValidator = result.get('bar/foo/CustomInputValidator').get(0)
    inputValidator.getType() == SecurityControlType.INPUT_VALIDATOR
    inputValidator.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    inputValidator.getClassName() == "bar/foo/CustomInputValidator"
    inputValidator.getMethod() == "validate"
    inputValidator.getParameterTypes() == null
    inputValidator.getParametersToMark() == null

    def sanitizer = result.get('bar/foo/CustomSanitizer').get(0)
    sanitizer.getType() == SecurityControlType.SANITIZER
    sanitizer.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    sanitizer.getClassName() == "bar/foo/CustomSanitizer"
    sanitizer.getMethod() == "sanitize"
    sanitizer.getParameterTypes() == null
    sanitizer.getParametersToMark() == null
  }

  void 'test multiple secure marks'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'INPUT_VALIDATOR:COMMAND_INJECTION,SQL_INJECTION:bar.foo.CustomInputValidator:validate'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControl = result.get('bar/foo/CustomInputValidator').get(0)
    securityControl.getType() == SecurityControlType.INPUT_VALIDATOR
    securityControl.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.SQL_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    securityControl.getClassName() == "bar/foo/CustomInputValidator"
    securityControl.getMethod() == "validate"
    securityControl.getParameterTypes() == null
    securityControl.getParametersToMark() == null
  }

  void 'test overcharged methods'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:java.lang.Object,java.lang.String,java.lang.String'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControl = result.get('bar/foo/CustomInputValidator').get(0)
    securityControl.getType() == SecurityControlType.INPUT_VALIDATOR
    securityControl.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    securityControl.getClassName() == "bar/foo/CustomInputValidator"
    securityControl.getMethod() == "validate"
    securityControl.getParameterTypes() == ["java.lang.Object", "java.lang.String", "java.lang.String"]
    securityControl.getParametersToMark() == null
  }

  void 'test parameters to mark'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:1,2'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControl = result.get('bar/foo/CustomInputValidator').get(0)
    securityControl.getType() == SecurityControlType.INPUT_VALIDATOR
    securityControl.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    securityControl.getClassName() == "bar/foo/CustomInputValidator"
    securityControl.getMethod() == "validate"
    securityControl.getParameterTypes() == null
    securityControl.getParametersToMark().cardinality() == 2
    securityControl.getParametersToMark().get(1)
    securityControl.getParametersToMark().get(2)
  }

  void 'test overcharged methods with parameters to mark'() {
    setup:
    final formatter = new SecurityControlFormatter()
    final config = 'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:java.lang.Object,java.lang.String,java.lang.String:1,2'
    final result = formatter.format(config)

    expect:
    result.size() == 1
    def securityControl = result.get('bar/foo/CustomInputValidator').get(0)
    securityControl.getType() == SecurityControlType.INPUT_VALIDATOR
    securityControl.getMarks() == (VulnerabilityMarks.COMMAND_INJECTION_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)
    securityControl.getClassName() == "bar/foo/CustomInputValidator"
    securityControl.getMethod() == "validate"
    securityControl.getParameterTypes() == ["java.lang.Object", "java.lang.String", "java.lang.String"]
    securityControl.getParametersToMark().cardinality() == 2
    securityControl.getParametersToMark().get(1)
    securityControl.getParametersToMark().get(2)
  }

  void 'test error control'() {
    setup:
    final formatter = new SecurityControlFormatter()
    Throwable thrown = null
    def result = null

    when:
    try {
      result = formatter.format(config)
    } catch (Throwable t) {
      thrown = t
    }

    then:
    thrown == null
    result == null

    where:
    config << [
      '',
      'This is not a valid configuration',
      'INPUT_VALIDATOR',
      'INPUT_VALIDATOR:COMMAND_INJECTION',
      'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator',
      'INPUT_VALIDATOR:COMMAND_INJECTION:bar.foo.CustomInputValidator:validate:1,2:java.lang.Object,java.lang.String,java.lang.String'
    ]
  }
}
