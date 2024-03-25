package datadog.trace.instrumentation.commonstext

import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringEscapeUtilsSuite

class StringEscapeUtilsCallSiteTest extends IastAgentTestRunner {

  void 'test #method with XSS mark'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    def result = computeUnderIastTrace { TestStringEscapeUtilsSuite.&"$method".call(args) }

    then:
    result == expected
    1 * module.taintIfTainted(_ as IastContext, _ as String, args[0], false, VulnerabilityMarks.XSS_MARK)
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
    def result = computeUnderIastTrace { TestStringEscapeUtilsSuite.&"$method".call(args) }

    then:
    result == expected
    1 * module.taintIfTainted(_ as IastContext, _ as String, args[0])
    0 * _

    where:
    method       | args                  | expected
    'escapeJson' | ['Ø-This is a quote'] | '\\u00D8-This is a quote'
  }
}
