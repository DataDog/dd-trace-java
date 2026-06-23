package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFileReaderCharsetSuite

import java.nio.charset.Charset

class FileReaderCharsetCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP new FileReader with String path and Charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_fr_str_cs.txt')

    when:
    TestFileReaderCharsetSuite.newFileReader(file.path, Charset.defaultCharset()).close()

    then:
    1 * helper.beforeFileLoaded(file.path)
  }

  void 'test RASP new FileReader with File and Charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_fr_file_cs.txt')

    when:
    TestFileReaderCharsetSuite.newFileReader(file, Charset.defaultCharset()).close()

    then:
    1 * helper.beforeFileLoaded(file.path)
  }
}
