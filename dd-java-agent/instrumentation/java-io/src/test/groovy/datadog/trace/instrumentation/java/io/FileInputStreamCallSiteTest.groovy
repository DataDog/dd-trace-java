package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestFileInputStreamSuite

class FileInputStreamCallSiteTest extends BaseIoCallSiteTest {

  def 'test new file input stream with path'() {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = newFile('test.txt').toString()

    when:
    TestFileInputStreamSuite.newFileInputStream(path)

    then:
    1 * iastModule.onPathTraversal(path)
    0 * _
  }
}
