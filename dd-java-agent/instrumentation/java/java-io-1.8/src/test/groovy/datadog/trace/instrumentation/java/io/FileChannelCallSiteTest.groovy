package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFileChannelSuite

import java.nio.file.StandardOpenOption

class FileChannelCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP FileChannel.open read-only fires only beforeFileLoaded'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_fc_read.txt').toPath()

    when:
    TestFileChannelSuite.openRead(path).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
    0 * helper.beforeFileWritten(_)
  }

  void 'test RASP FileChannel.open write fires both beforeFileLoaded and beforeFileWritten'() {
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

  void 'test RASP FileChannel.open with Set READ-only fires only beforeFileLoaded'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_fc_set.txt').toPath()
    final options = EnumSet.of(StandardOpenOption.READ)

    when:
    TestFileChannelSuite.openWithSet(path, options).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
    0 * helper.beforeFileWritten(_)
  }

  void 'test RASP FileChannel.open with Set WRITE fires both callbacks'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_fc_set_write.txt')
    final options = EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE)

    when:
    TestFileChannelSuite.openWithSet(path, options).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP FileChannel.open with no options fires only beforeFileLoaded'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_fc_default.txt').toPath()

    when:
    TestFileChannelSuite.openWithOptions(path).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
    0 * helper.beforeFileWritten(_)
  }
}
