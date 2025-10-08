package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.instrumentation.java.lang.FileLoadedRaspHelper
import foo.bar.TestFileInputStreamSuite

class FileInputStreamCallSiteTest extends BaseIoRaspCallSiteTest {

  void  'test IAST new file input stream with path'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = newFile('test_iast.txt').toString()

    when:
    TestFileInputStreamSuite.newFileInputStream(path)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void  'test RASP new file input stream with path'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final path = newFile('test_rasp.txt').toString()

    when:
    TestFileInputStreamSuite.newFileInputStream(path)

    then:
    1 * helper.beforeFileLoaded(path)
  }
}
