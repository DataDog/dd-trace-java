package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFileChannelSuite

import java.nio.file.StandardOpenOption

class FileChannelCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP FileChannel.open read-only fires beforeFileLoaded'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_fc_read.txt').toPath()

    when:
    TestFileChannelSuite.openRead(path).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP FileChannel.open write fires beforeFileWritten'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_fc_write.txt')

    when:
    TestFileChannelSuite.openWrite(path).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP FileChannel.open with Set of options'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_fc_set.txt').toPath()
    final options = EnumSet.of(StandardOpenOption.READ)

    when:
    TestFileChannelSuite.openWithSet(path, options).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
    1 * helper.beforeFileWritten(path.toString())
  }
}
