package datadog.trace.instrumentation.commonstext

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringEscapeUtilsSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class StringEscapeUtilsCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test #method with XSS mark'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringEscapeUtilsSuite.&"$method".call(args)

    then:
    result == expected
    1 * module.taintStringIfTainted(_ as String, args[0], false, VulnerabilityMarks.HTML_ESCAPED_MARK)
    0 * _

    where:
    method             | args                  | expected
    'escapeEcmaScript' | ['Ø-This is a quote'] | '\\u00D8-This is a quote'
    'escapeHtml3'      | ['Ø-This is a quote'] | '&Oslash;-This is a quote'
    'escapeHtml4'      | ['Ø-This is a quote'] | '&Oslash;-This is a quote'
    'escapeJava'       | ['Ø-This is a quote'] | '\\u00D8-This is a quote'
    'escapeXml10'      | ['Ø-This is a quote'] | 'Ø-This is a quote'
    'escapeXml11'      | ['Ø-This is a quote'] | 'Ø-This is a quote'
  }

  void 'test #method propagation'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringEscapeUtilsSuite.&"$method".call(args)

    then:
    result == expected
    1 * module.taintStringIfTainted(_ as String, args[0])
    0 * _

    where:
    method       | args                  | expected
    'escapeJson' | ['Ø-This is a quote'] | '\\u00D8-This is a quote'
  }
}
