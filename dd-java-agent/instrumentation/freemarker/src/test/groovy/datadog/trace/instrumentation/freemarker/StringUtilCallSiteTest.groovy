package datadog.trace.instrumentation.freemarker

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringUtilSuite

class StringUtilCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test #method'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringUtilSuite.&"$method".call(args)

    then:
    result == expected
    1 * module.taintIfInputIsTaintedWithMarks(_ as String, args[0], VulnerabilityMarks.XSS_MARK)
    0 * _

    where:
    method       | args                  | expected
    'HTMLEnc' | ['<htmlTag>"escape this < </htmlTag>'] | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
    'XMLEnc' | ['<xmlTag>"escape this < </xmlTag>'] | '&lt;xmlTag&gt;&quot;escape this &lt; &lt;/xmlTag&gt;'
    'XHTMLEnc' | ['<htmlTag>"escape this < </htmlTag>'] | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
    'javaStringEnc' | ['<script>function a(){console.log("escape this < ")}<script>'] | '<script>function a(){console.log(\\"escape this < \\")}<script>'
    'javaScriptStringEnc' | ['<script>function a(){console.log("escape this < ")}<script>'] | '<script>function a(){console.log(\\"escape this < \\")}<script>'
    'jsonStringEnc' | ['["a":{"b":2}]'] | '[\\"a\\":{\\"b\\":2}]'
  }

  void 'test #method with null args'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestStringUtilSuite.&"$method".call(null)

    then:
    def thrownException = thrown (Exception)
    assert  thrownException.stackTrace[0].getClassName().startsWith('freemarker')
    0 * module.taintIfInputIsTaintedWithMarks(_)
    0 * _

    where:
    method       | _
    'HTMLEnc' | _
    'XMLEnc' | _
    'XHTMLEnc' | _
    'javaStringEnc' | _
    'javaScriptStringEnc' | _
    'jsonStringEnc' | _
  }
}
