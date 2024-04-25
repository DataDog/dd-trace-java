package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringReaderSuite

class StringReaderCallSiteTest extends  BaseIoCallSiteTest{

  void 'test StringReader.<init>'(){
    given:
    PropagationModule iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final input = 'Test input'

    when:
    TestStringReaderSuite.init(input)

    then:
    1 * iastModule.taintObjectIfTainted(_ as StringReader, input)
  }
}
