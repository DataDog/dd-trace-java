package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFilesJava11Suite

import java.nio.charset.StandardCharsets

class FilesJava11CallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP Files.writeString without charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_writestring.txt')

    when:
    TestFilesJava11Suite.writeString(path, 'hello')

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.writeString with charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_writestring_cs.txt')

    when:
    TestFilesJava11Suite.writeString(path, 'hello', StandardCharsets.UTF_8)

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.readString without charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_readstring.txt').toPath()

    when:
    TestFilesJava11Suite.readString(path)

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.readString with charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_readstring_cs.txt').toPath()

    when:
    TestFilesJava11Suite.readString(path, StandardCharsets.UTF_8)

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }
}
