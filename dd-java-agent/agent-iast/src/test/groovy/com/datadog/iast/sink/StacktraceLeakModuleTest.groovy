package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.sink.StacktraceLeakModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class StacktraceLeakModuleTest extends IastModuleImplTestBase {
  private StacktraceLeakModule module

  def setup() {
    module = registerDependencies(new StacktraceLeakModuleImpl())
  }

  void 'iast stacktrace leak module'() {
    given:
    final spanId = 123456
    final span = Mock(AgentSpan)

    def throwable = new Exception('some exception')
    def moduleName = 'moduleName'
    def className = 'className'
    def methodName = 'methodName'

    when:
    module.onStacktraceLeak(throwable, moduleName, className, methodName)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getSpanId() >> spanId
    1 * span.getServiceName()
    1 * reporter.report(_, _) >> { args ->
      Vulnerability vuln = args[1] as Vulnerability
      assert vuln != null
      assert vuln.getType() == VulnerabilityType.STACKTRACE_LEAK
      assert vuln.getEvidence() == new Evidence('ExceptionHandler in moduleName \r\nthrown java.lang.Exception')
      assert vuln.getLocation() != null
    }
    0 * _
  }

  void 'iast stacktrace leak no exception'() {
    when:
    module.onStacktraceLeak(null, null, null, null)

    then:
    0 * _
  }
}
