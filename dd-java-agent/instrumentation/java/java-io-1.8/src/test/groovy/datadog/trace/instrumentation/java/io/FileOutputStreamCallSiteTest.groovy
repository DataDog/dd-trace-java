package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.instrumentation.java.lang.FileIORaspHelper
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
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_1.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path)

    then:
    1 * helper.beforeFileWritten(path)
  }

  void 'test RASP new file input stream with path and append'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_2.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path, false)

    then:
    1 * helper.beforeFileWritten(path)
  }

  void 'test RASP new file output stream with file'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_file_1.txt')

    when:
    TestFileOutputStreamSuite.newFileOutputStream(file)

    then:
    1 * helper.beforeFileWritten(file.path)
  }

  void 'test RASP new file output stream with file and append'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_file_2.txt')

    when:
    TestFileOutputStreamSuite.newFileOutputStream(file, false)

    then:
    1 * helper.beforeFileWritten(file.path)
  }
}
