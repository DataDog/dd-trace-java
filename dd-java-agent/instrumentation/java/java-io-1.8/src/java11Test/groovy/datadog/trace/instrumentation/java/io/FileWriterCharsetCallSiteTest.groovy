package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFileWriterCharsetSuite

import java.nio.charset.Charset

class FileWriterCharsetCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP new FileWriter with String path and Charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_fw_str_cs.txt')

    when:
    TestFileWriterCharsetSuite.newFileWriter(file.path, Charset.defaultCharset()).close()

    then:
    1 * helper.beforeFileWritten(file.path)
  }

  void 'test RASP new FileWriter with String path, Charset, and append flag'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_fw_str_cs_append.txt')

    when:
    TestFileWriterCharsetSuite.newFileWriter(file.path, Charset.defaultCharset(), false).close()

    then:
    1 * helper.beforeFileWritten(file.path)
  }

  void 'test RASP new FileWriter with File and Charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_fw_file_cs.txt')

    when:
    TestFileWriterCharsetSuite.newFileWriter(file, Charset.defaultCharset()).close()

    then:
    1 * helper.beforeFileWritten(file.path)
  }

  void 'test RASP new FileWriter with File, Charset, and append flag'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_fw_file_cs_append.txt')

    when:
    TestFileWriterCharsetSuite.newFileWriter(file, Charset.defaultCharset(), false).close()

    then:
    1 * helper.beforeFileWritten(file.path)
  }
}
