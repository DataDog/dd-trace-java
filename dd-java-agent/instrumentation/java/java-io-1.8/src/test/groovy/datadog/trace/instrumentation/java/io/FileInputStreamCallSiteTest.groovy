package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.instrumentation.java.lang.FileIORaspHelper
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
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp.txt').toString()

    when:
    TestFileInputStreamSuite.newFileInputStream(path)

    then:
    1 * helper.beforeFileLoaded(path)
  }

  void 'test RASP new file input stream with file'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_file.txt')

    when:
    TestFileInputStreamSuite.newFileInputStream(file)

    then:
    1 * helper.beforeFileLoaded(file.path)
  }
}
