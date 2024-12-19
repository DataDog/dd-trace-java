package com.datadog.iast.securitycontrol

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.securitycontrol.SecurityControl
import datadog.trace.api.iast.securitycontrol.SecurityControlFormatter
import datadog.trace.test.util.DDSpecification
import foo.bar.securitycontrol.SecurityControlStaticTestSuite
import foo.bar.securitycontrol.SecurityControlTestSuite
import net.bytebuddy.agent.ByteBuddyAgent

import java.lang.instrument.Instrumentation

class IastSecurityControlTransformerForkedTest extends DDSpecification{

  //static methods
  private static final String STATIC_SANITIZER = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:sanitize'
  private static final String STATIC_SANITIZE_VALIDATE_OBJECT = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:sanitizeObject'
  private static final String STATIC_SANITIZE_INPUTS= 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:sanitizeInputs'
  private static final String STATIC_SANITIZE_MANY_INPUTS= 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:sanitizeManyInputs'
  private static final String STATIC_SANITIZE_INT = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:sanitizeInt'
  private static final String STATIC_SANITIZE_LONG = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:sanitizeLong'
  private static final String STATIC_INPUT_VALIDATOR_VALIDATE_ALL = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:validateAll'
  private static final String STATIC_INPUT_VALIDATOR_VALIDATE_OVERLOADED = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:validate:java.lang.Object,java.lang.String,java.lang.String:1,2'
  private static final String STATIC_INPUT_VALIDATOR_VALIDATE_RETURNING_INT = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:validateReturningInt'
  private static final String STATIC_INPUT_VALIDATOR_VALIDATE_OBJECT = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:validateObject'
  private static final String STATIC_INPUT_VALIDATOR_VALIDATE_LONG = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:validateLong'
  private static final String STATIC_INPUT_VALIDATOR_VALIDATE_SELECTED_LONG = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlStaticTestSuite:validateSelectedLong:0'

  //not static methods
  private static final String SANITIZER = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:sanitize'
  private static final String SANITIZE_VALIDATE_OBJECT = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:sanitizeObject'
  private static final String SANITIZE_INPUTS= 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:sanitizeInputs'
  private static final String SANITIZE_MANY_INPUTS= 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:sanitizeManyInputs'
  private static final String SANITIZE_INT = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:sanitizeInt'
  private static final String SANITIZE_LONG = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:sanitizeLong'
  private static final String INPUT_VALIDATOR_VALIDATE_ALL = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validateAll'
  private static final String INPUT_VALIDATOR_VALIDATE_OVERLOADED = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validate:java.lang.Object,java.lang.String,java.lang.String:1,2'
  private static final String INPUT_VALIDATOR_VALIDATE_RETURNING_INT = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validateReturningInt'
  private static final String INPUT_VALIDATOR_VALIDATE_OBJECT = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validateObject'
  private static final String INPUT_VALIDATOR_VALIDATE_LONG = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validateLong'
  private static final String INPUT_VALIDATOR_VALIDATE_SELECTED_LONG = 'INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validateSelectedLong:0'



  def setupSpec() {
    final staticConfig = "${STATIC_SANITIZER};${STATIC_SANITIZE_VALIDATE_OBJECT};${STATIC_SANITIZE_INPUTS};${STATIC_SANITIZE_MANY_INPUTS};${STATIC_SANITIZE_INT};${STATIC_SANITIZE_LONG};${STATIC_INPUT_VALIDATOR_VALIDATE_ALL};${STATIC_INPUT_VALIDATOR_VALIDATE_OVERLOADED};${STATIC_INPUT_VALIDATOR_VALIDATE_RETURNING_INT};${STATIC_INPUT_VALIDATOR_VALIDATE_OBJECT};${STATIC_INPUT_VALIDATOR_VALIDATE_LONG};${STATIC_INPUT_VALIDATOR_VALIDATE_SELECTED_LONG}"
    final config =  "${SANITIZER};${SANITIZE_VALIDATE_OBJECT};${SANITIZE_INPUTS};${SANITIZE_MANY_INPUTS};${SANITIZE_INT};${SANITIZE_LONG};${INPUT_VALIDATOR_VALIDATE_ALL};${INPUT_VALIDATOR_VALIDATE_OVERLOADED};${INPUT_VALIDATOR_VALIDATE_RETURNING_INT};${INPUT_VALIDATOR_VALIDATE_OBJECT};${INPUT_VALIDATOR_VALIDATE_LONG};${INPUT_VALIDATOR_VALIDATE_SELECTED_LONG}"
    final fullConfig = "${staticConfig};${config}"
    Instrumentation instrumentation =  ByteBuddyAgent.install()
    Map<String,List<SecurityControl>> securityControls =
      SecurityControlFormatter.format(fullConfig)
    assert securityControls != null
    instrumentation.addTransformer(new IastSecurityControlTransformer(securityControls), true)
  }


  void 'test sanitize'(){
    given:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final marks = (VulnerabilityMarks.XSS_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)

    when:
    SecurityControlStaticTestSuite.&"$method".call(*args)

    then:
    expected * iastModule.markIfTainted( toSanitize, marks)
    0 * _

    when:
    final suite = new SecurityControlTestSuite()
    suite.&"$method".call(*args)

    then:
    expected * iastModule.markIfTainted( toSanitize, marks)
    0 * _

    where:
    method               | args                                                                                       | toSanitize  | expected
    'sanitize'           | ['test']                                                                                   | 'Sanitized' | 1
    'sanitizeObject'     | ['test']                                                                                   | 'Sanitized' | 1
    'sanitizeInputs'     | ['test1', new Object(), 27i]                                                               | 'Sanitized' | 1
    'sanitizeManyInputs' | ['test', 'test2', 'test3', 'test4', 'test5', 'test6', 'test7', 'test8', 'test9', 'test10'] | 'Sanitized' | 1
    'sanitizeInt'        | [1i]                                                                                       | args        | 0
    'sanitizeLong'       | [1L]                                                                                       | args        | 0
  }

  void 'test validate'(){
    given:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final marks = (VulnerabilityMarks.XSS_MARK | VulnerabilityMarks.CUSTOM_SECURITY_CONTROL_MARK)

    when:
    SecurityControlStaticTestSuite.&"$method".call(*args)

    then:
    for (final validate : toValidate){
      expected * iastModule.markIfTainted(validate, marks)
    }
    0 * _

    when:
    final suite = new SecurityControlTestSuite()
    suite.&"$method".call(*args)

    then:
    for (final validate : toValidate){
      expected * iastModule.markIfTainted(validate, marks)
    }
    0 * _

    where:
    method                 | args                                                                                       | toValidate         | expected
    'validateAll'          | ['test']                                                                                   | args               | 1
    'validateAll'          | ['test1', 'test2']                                                                         | args               | 1
    'validateAll'          | [1L, 'test2']                                                                              | [args[1]]          | 1
    'validateAll'          | ['test', 'test2', 'test3', 'test4', 'test5', 'test6', 'test7', 'test8', 'test9', 'test10'] | args               | 1
    'validate'             | ['test']                                                                                   | args               | 0
    'validate'             | [new Object(), 'test1', 'test2']                                                           | [args[1], args[2]] | 1
    'validateReturningInt' | ['test']                                                                                   | args               | 1
    'validateObject'       | [new Object()]                                                                             | args               | 1
    'validateLong'         | [1L, 'test2']                                                                              | [args[1]]          | 1
    'validateLong'         | ['test2', 2L]                                                                              | [args[0]]          | 1
    'validateLong'         | [1L, 'test2', 2L]                                                                          | [args[1]]          | 1
    'validateSelectedLong' | [1L]                                                                                       | args               | 0
    'validateSelectedLong' | [1L, 2L]                                                                                   | [args[0]]          | 0
  }
}
