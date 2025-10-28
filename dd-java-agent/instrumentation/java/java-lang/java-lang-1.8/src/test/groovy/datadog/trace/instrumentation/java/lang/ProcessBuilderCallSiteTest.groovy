package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.CommandInjectionModule
import foo.bar.TestProcessBuilderSuite

class ProcessBuilderCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }


  def 'test start'() {
    setup:
    final command = ['you_cant_run_me', '-lah']
    CommandInjectionModule iastModule = Mock(CommandInjectionModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    TestProcessBuilderSuite.start(command)

    then:
    thrown(IOException)
    1 * iastModule.onProcessBuilderStart(command)
    _ * TEST_PROFILING_CONTEXT_INTEGRATION._
    0 * _
  }
}
