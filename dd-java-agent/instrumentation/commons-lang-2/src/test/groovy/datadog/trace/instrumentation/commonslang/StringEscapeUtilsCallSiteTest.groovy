package datadog.trace.instrumentation.commonslang

import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringEscapeUtilsSuite

import static datadog.trace.api.iast.VulnerabilityMarks.SQL_INJECTION_MARK
import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK

class StringEscapeUtilsCallSiteTest extends IastAgentTestRunner {

  void 'test #method'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    def result = computeUnderIastTrace { TestStringEscapeUtilsSuite.&"$method".call(args) }

    then:
    result == expected
    1 * module.taintIfTainted(_ as IastContext, _ as String, args[0], false, mark)
    0 * _

    where:
    method             | args                  | mark               | expected
    'escapeHtml'       | ['Ø-This is a quote'] | XSS_MARK           | '&Oslash;-This is a quote'
    'escapeJava'       | ['Ø-This is a quote'] | XSS_MARK           | '\\u00D8-This is a quote'
    'escapeJavaScript' | ['Ø-This is a quote'] | XSS_MARK           | '\\u00D8-This is a quote'
    'escapeXml'        | ['Ø-This is a quote'] | XSS_MARK           | '&#216;-This is a quote'
    'escapeSql'        | ['Ø-This is a quote'] | SQL_INJECTION_MARK | 'Ø-This is a quote'
  }
}
