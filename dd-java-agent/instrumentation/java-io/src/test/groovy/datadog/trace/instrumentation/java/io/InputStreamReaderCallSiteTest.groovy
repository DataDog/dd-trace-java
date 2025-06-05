package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestInputStreamReaderSuite

import java.nio.charset.Charset

class InputStreamReaderCallSiteTest extends  BaseIoCallSiteTest{

  void 'test InputStreamReader.<init>'(){
    given:
    PropagationModule iastModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    TestInputStreamReaderSuite.init(*args)

    then:
    1 * iastModule.taintObjectIfTainted(_ as InputStreamReader, _ as InputStream)
    0 * _

    where:
    args << [
      [new ByteArrayInputStream("test".getBytes()), Charset.defaultCharset()],
      // InputStream input
      [new ByteArrayInputStream("test".getBytes())]// Reader input
    ]
  }
}
