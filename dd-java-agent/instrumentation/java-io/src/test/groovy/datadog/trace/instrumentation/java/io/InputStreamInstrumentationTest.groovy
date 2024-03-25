package datadog.trace.instrumentation.java.io

import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestInputStreamSuite

class InputStreamInstrumentationTest extends IastAgentTestRunner {

  def 'test constructor with IS as arg()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final is = Mock(InputStream)

    when:
    runUnderIastTrace { TestInputStreamSuite.pushbackInputStreamFromIS(is) }

    then:
    (1.._) * propagationModule.taintIfTainted(_ as IastContext, _ as InputStream, is)
  }
}
