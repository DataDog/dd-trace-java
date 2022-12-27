package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import foo.bar.TestFileOutputStreamSuite

class FileOutputStreamCallSiteTest extends BaseIoCallSiteTest {

  def 'test new file input stream with path'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = newFile('test.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path)

    then:
    1 * iastModule.onPathTraversal(path)
    0 * _
  }
}
