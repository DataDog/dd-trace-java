package com.datadog.iast.securitycontrol

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.securitycontrol.SecurityControl
import datadog.trace.api.iast.securitycontrol.SecurityControlFormatter
import datadog.trace.test.util.DDSpecification
import foo.bar.securitycontrol.SecurityControlTestSuite
import net.bytebuddy.agent.ByteBuddyAgent

import java.lang.instrument.Instrumentation

class IastSecurityControlTransformerForkedTest extends DDSpecification{

  def setupSpec() {
    final config = 'SANITIZER:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:sanitize;INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validateAll;INPUT_VALIDATOR:XSS:foo.bar.securitycontrol.SecurityControlTestSuite:validate:1,2;java.lang.Object,java.lang.String,java.lang.String'
    Instrumentation instrumentation =  ByteBuddyAgent.install()
    List<SecurityControl> securityControls =
      SecurityControlFormatter.format(config)
    assert securityControls != null
    instrumentation.addTransformer(new IastSecurityControlTransformer(securityControls), true)
  }


  void 'test sanitize'(){
    given:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final marks = VulnerabilityMarks.XSS_MARK

    when:
    SecurityControlTestSuite.sanitize('test')

    then:
    1 * iastModule.markIfTainted('Sanitized test', marks)
    0 * _
  }

  void 'test validate'(){
    given:
    final iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final marks = VulnerabilityMarks.XSS_MARK

    when:
    SecurityControlTestSuite.&"$method".call(args)

    then:
    for (final validate : toValidate){
      expected * iastModule.markIfTainted(validate, marks)
    }
    0 * _

    where:
    method        | args                             | toValidate         | expected
    'validateAll' | ['test']                         | [args[0]]          | 1
    'validateAll' | ['test1', "test2"]               | [args[0], args[1]]          | 1
    'validate'    | ['test']                         | args[0]            | 0
    'validate'    | [new Object(), 'test1', "test2"] | [args[1], args[2]] | 1
  }
}
