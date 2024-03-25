package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringReaderSuite

class StringReaderCallSiteTest extends BaseIoCallSiteTest {

  void 'test StringReader.<init>'() {
    given:
    PropagationModule iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final input = 'Test input'

    when:
    runUnderIastTrace { TestStringReaderSuite.init(input) }

    then:
    1 * iastModule.taintIfTainted(_ as IastContext, _ as StringReader, input)
  }
}
