package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestCustomStringReader
import foo.bar.TestStringReaderSuite

class StringReaderCallSiteTest extends BaseIoCallSiteTest {

  void 'test StringReader.<init>'() {
    given:
    PropagationModule iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final input = 'Test input'

    when:
    TestStringReaderSuite.init(input)

    then:
    1 * iastModule.taintObjectIfTainted(_ as StringReader, input)
  }

  void 'test super call to StringReader.<init>'() {
    given:
    PropagationModule iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final input = 'Test input'

    when:
    new TestCustomStringReader(input)

    then:
    // new StringReader
    3 * iastModule.taintObjectIfTainted(
      { it -> !(it instanceof TestCustomStringReader) }, { String it ->
        it.startsWith("New")
      }
      )

    // super(...)
    1 * iastModule.taintObjectIfTainted(
      { it instanceof TestCustomStringReader },
      { String it -> it.startsWith("Super") })
  }
}
