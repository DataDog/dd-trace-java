import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestEscapeUtilsSuite

class EscapeUtilsCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test #method'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestEscapeUtilsSuite.&"$method".call(args)

    then:
    result == expected
    1 * module.taintStringIfTainted(_ as String, args[0], false, VulnerabilityMarks.XSS_MARK)
    0 * _

    where:
    method             | args                                                            | expected
    'htmlEscape'       | ['Ø-This is a quote']                                           | '&Oslash;-This is a quote'
    'htmlEscape'       | ['Ø-This is a quote', 'ISO-8859-1']                             | '&Oslash;-This is a quote'
    'javaScriptEscape' | ['<script>function a(){console.log("escape this < ")}<script>'] | '\\u003Cscript\\u003Efunction a(){console.log(\\"escape this \\u003C \\")}\\u003Cscript\\u003E'
  }

  void 'test #method with null args throws exception'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestEscapeUtilsSuite.&"$method".call(args)

    then:
    def ex = thrown(Exception)
    assert ex.stackTrace[0].getClassName().startsWith('org.springframework')
    0 * _

    where:
    method             | args
    'htmlEscape'       | [null]
    'htmlEscape'       | [null, null]
    'javaScriptEscape' | [null]
  }
}
