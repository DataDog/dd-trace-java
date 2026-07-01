package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFileWriterSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class FileWriterCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP new file writer with path'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_writer_1.txt').toString()

    when:
    TestFileWriterSuite.newFileWriter(path)

    then:
    1 * helper.beforeFileWritten(path)
  }

  void 'test RASP new file writer with path and append'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_writer_2.txt').toString()

    when:
    TestFileWriterSuite.newFileWriter(path, false)

    then:
    1 * helper.beforeFileWritten(path)
  }

  void 'test RASP new file writer with file'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_writer_file_1.txt')

    when:
    TestFileWriterSuite.newFileWriter(file)

    then:
    1 * helper.beforeFileWritten(file.path)
  }

  void 'test RASP new file writer with file and append'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_writer_file_2.txt')

    when:
    TestFileWriterSuite.newFileWriter(file, false)

    then:
    1 * helper.beforeFileWritten(file.path)
  }
}
