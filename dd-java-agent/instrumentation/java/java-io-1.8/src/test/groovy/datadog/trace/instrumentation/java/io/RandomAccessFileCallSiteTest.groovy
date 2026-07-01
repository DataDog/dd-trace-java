package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestRandomAccessFileSuite

class RandomAccessFileCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP RandomAccessFile with String path read-only mode'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_raf_r.txt')

    when:
    TestRandomAccessFileSuite.newRandomAccessFile(file.path, 'r')

    then:
    1 * helper.beforeRandomAccessFileOpened(file.path, 'r')
  }

  void 'test RASP RandomAccessFile with String path read-write mode'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_raf_rw.txt')

    when:
    TestRandomAccessFileSuite.newRandomAccessFile(file.path, 'rw')

    then:
    1 * helper.beforeRandomAccessFileOpened(file.path, 'rw')
  }

  void 'test RASP RandomAccessFile with File read-only mode'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_raf_file_r.txt')

    when:
    TestRandomAccessFileSuite.newRandomAccessFile(file, 'r')

    then:
    1 * helper.beforeRandomAccessFileOpened(file.path, 'r')
  }

  void 'test RASP RandomAccessFile with File read-write mode'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final file = newFile('test_rasp_raf_file_rw.txt')

    when:
    TestRandomAccessFileSuite.newRandomAccessFile(file, 'rw')

    then:
    1 * helper.beforeRandomAccessFileOpened(file.path, 'rw')
  }
}
