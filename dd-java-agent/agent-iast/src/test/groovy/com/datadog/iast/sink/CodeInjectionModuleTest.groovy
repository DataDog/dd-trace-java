package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.taint.Ranges
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.CodeInjectionModule

class CodeInjectionModuleTest extends IastModuleImplTestBase {

  private CodeInjectionModule module

  def setup() {
    module = new CodeInjectionModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'test null or empty script is ignored'() {
    when: 'cast disambiguates the onEval(String) / onEval(Reader) overloads for a null argument'
    module.onEval(script as String)

    then:
    0 * _

    where:
    script | _
    null   | _
    ''     | _
  }

  void 'test code injection detection on string'() {
    when:
    module.onEval(script)

    then: 'report is not called if no active span'
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    when:
    module.onEval(script)

    then: 'report is not called if the script is not tainted'
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)

    when:
    taint(script)
    module.onEval(script)

    then: 'report is called when the script is tainted'
    tracer.activeSpan() >> span
    1 * reporter.report(span, { Vulnerability vul -> vul.type == VulnerabilityType.CODE_INJECTION })

    where:
    script = '2 + 2'
  }

  void 'test code injection detection on StringReader'() {
    given:
    final reader = new StringReader('2 + 2')

    when:
    module.onEval(reader)

    then: 'report is not called if the reader is not tainted'
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)

    when:
    taint(reader)
    module.onEval(reader)

    then: 'report is called when the reader is tainted'
    tracer.activeSpan() >> span
    1 * reporter.report(span, { Vulnerability vul -> vul.type == VulnerabilityType.CODE_INJECTION })
  }

  void 'test code injection detection on InputStreamReader'() {
    given:
    final reader = new InputStreamReader(new ByteArrayInputStream('2 + 2'.bytes))

    when:
    module.onEval(reader)

    then: 'report is not called if the reader is not tainted'
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)

    when:
    taint(reader)
    module.onEval(reader)

    then: 'report is called when the reader is tainted'
    tracer.activeSpan() >> span
    1 * reporter.report(span, { Vulnerability vul -> vul.type == VulnerabilityType.CODE_INJECTION })

    cleanup:
    reader.close()
  }

  void 'test unsupported Reader type is ignored even when tainted'() {
    given: 'only StringReader and InputStreamReader are inspected (see CodeInjectionModuleImpl.onEval)'
    final reader = new CharArrayReader('2 + 2'.toCharArray())
    taint(reader)

    when:
    module.onEval(reader)

    then:
    0 * _

    cleanup:
    reader.close()
  }

  void 'if all ranges of the tainted script have the code injection mark it is not reported'() {
    given:
    final script = '2 + 2'
    final Range[] ranges = [
      new Range(0, 1, new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value'), VulnerabilityMarks.CODE_INJECTION_MARK)
    ]
    ctx.getTaintedObjects().taint(script, ranges)

    when:
    module.onEval(script)

    then:
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)
  }

  void 'if all ranges of the tainted reader have the code injection mark it is not reported'() {
    given:
    final Range[] ranges = [
      new Range(0, 1, new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', 'value'), VulnerabilityMarks.CODE_INJECTION_MARK)
    ]
    ctx.getTaintedObjects().taint(reader, ranges)

    when:
    module.onEval(reader)

    then:
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)

    cleanup:
    reader.close()

    where:
    reader << [
      new StringReader('2 + 2'),
      new InputStreamReader(new ByteArrayInputStream('2 + 2'.bytes))
    ]
  }

  private taint(final Object value) {
    ctx.getTaintedObjects().taint(value, Ranges.forObject(new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', value.toString())))
  }
}
