package datadog.trace.instrumentation.unbescape

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
    'escapeHtml4Xml'   | ['<htmlTag>"escape this < </htmlTag>']                          | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
    'escapeHtml4'      | ['<htmlTag>"escape this < </htmlTag>']                          | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
    'escapeHtml5'      | ['<htmlTag>"escape this < </htmlTag>']                          | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
    'escapeHtml5Xml'   | ['<htmlTag>"escape this < </htmlTag>']                          | '&lt;htmlTag&gt;&quot;escape this &lt; &lt;/htmlTag&gt;'
    'escapeJavaScript' | ['<script>function a(){console.log("escape this < ")}<script>'] | '<script>function a(){console.log(\\"escape this < \\")}<script>'
  }

  void 'test #method with null args not thrown exception'() {

    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestEscapeUtilsSuite.&"$method".call(null)

    then:
    notThrown(Exception)
    0 * _

    where:
    method             | _
    'escapeHtml4Xml'   | _
    'escapeHtml4'      | _
    'escapeHtml5'      | _
    'escapeHtml5Xml'   | _
    'escapeJavaScript' | _
  }
}
