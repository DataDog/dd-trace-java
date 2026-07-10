package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFileReaderSuite

class FileReaderCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP new file reader with path'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_reader.txt').toString()

    when:
    TestFileReaderSuite.newFileReader(path)

    then:
    1 * helper.beforeFileLoaded(path)
  }

  void 'test RASP new file reader with file'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_reader_file.txt')

    when:
    TestFileReaderSuite.newFileReader(file)

    then:
    1 * helper.beforeFileLoaded(file.path)
  }
}
