package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.instrumentation.java.lang.FileLoadedRaspHelper
import foo.bar.TestFileOutputStreamSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class FileOutputStreamCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test IAST new file input stream with path'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = newFile('test_iast_1.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void 'test IAST new file input stream with path and append'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = newFile('test_iast_2.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path, false)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void 'test RASP new file input stream with path'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_1.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path)

    then:
    1 * helper.beforeFileLoaded(path)
  }

  void 'test RASP new file input stream with path and append'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_2.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path, false)

    then:
    1 * helper.beforeFileLoaded(path)
  }
}
