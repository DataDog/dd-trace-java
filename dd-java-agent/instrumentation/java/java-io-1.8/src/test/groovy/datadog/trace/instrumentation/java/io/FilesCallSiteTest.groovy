package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestFilesSuite
import groovy.transform.CompileDynamic

import java.nio.charset.StandardCharsets

@CompileDynamic
class FilesCallSiteTest extends BaseIoRaspCallSiteTest {

  // ===================== WRITE =====================

  void 'test RASP Files.newOutputStream'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_newoutputstream.txt')

    when:
    TestFilesSuite.newOutputStream(path).close()

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.copy from InputStream'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final target = temporaryFolder.resolve('test_rasp_copy_target.txt')

    when:
    TestFilesSuite.copyFromStream(new ByteArrayInputStream(new byte[0]), target)

    then:
    1 * helper.beforeFileWritten(target.toString())
  }

  void 'test RASP Files.write byte array'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_write_bytes.txt')

    when:
    TestFilesSuite.write(path, 'hello'.bytes)

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.write lines with charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_write_lines_cs.txt')

    when:
    TestFilesSuite.writeLines(path, ['line1'], StandardCharsets.UTF_8)

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.write lines default charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_write_lines.txt')

    when:
    TestFilesSuite.writeLinesDefaultCharset(path, ['line1'])

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.newBufferedWriter with charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_bw_cs.txt')

    when:
    TestFilesSuite.newBufferedWriter(path, StandardCharsets.UTF_8).close()

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.newBufferedWriter default charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = temporaryFolder.resolve('test_rasp_bw.txt')

    when:
    TestFilesSuite.newBufferedWriterDefaultCharset(path).close()

    then:
    1 * helper.beforeFileWritten(path.toString())
  }

  void 'test RASP Files.copy path to path fires beforeFileLoaded on source and beforeFileWritten on target'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final source = newFile('test_rasp_copy_src.txt').toPath()
    final target = temporaryFolder.resolve('test_rasp_copy_path_dst.txt')

    when:
    TestFilesSuite.copyPathToPath(source, target)

    then:
    1 * helper.beforeFileLoaded(source.toString())
    1 * helper.beforeFileWritten(target.toString())
  }

  void 'test RASP Files.copy path to OutputStream fires beforeFileLoaded on source'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final source = newFile('test_rasp_copy_stream_src.txt').toPath()

    when:
    TestFilesSuite.copyToStream(source, new ByteArrayOutputStream())

    then:
    1 * helper.beforeFileLoaded(source.toString())
    0 * helper.beforeFileWritten(_)
  }

  void 'test RASP Files.move'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final source = newFile('test_rasp_move_src.txt').toPath()
    final target = temporaryFolder.resolve('test_rasp_move_dst.txt')

    when:
    TestFilesSuite.move(source, target)

    then:
    1 * helper.beforeFileWritten(target.toString())
  }

  // ===================== READ =====================

  void 'test RASP Files.newInputStream'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_newinputstream.txt').toPath()

    when:
    TestFilesSuite.newInputStream(path).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.readAllBytes'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_readallbytes.txt').toPath()

    when:
    TestFilesSuite.readAllBytes(path)

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.readAllLines with charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_readlines_cs.txt').toPath()

    when:
    TestFilesSuite.readAllLines(path, StandardCharsets.UTF_8)

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.readAllLines default charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_readlines.txt').toPath()

    when:
    TestFilesSuite.readAllLinesDefaultCharset(path)

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.newBufferedReader with charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_br_cs.txt').toPath()

    when:
    TestFilesSuite.newBufferedReader(path, StandardCharsets.UTF_8).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.newBufferedReader default charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_br.txt').toPath()

    when:
    TestFilesSuite.newBufferedReaderDefaultCharset(path).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.lines with charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_lines_cs.txt').toPath()

    when:
    TestFilesSuite.lines(path, StandardCharsets.UTF_8).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }

  void 'test RASP Files.lines default charset'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final path = newFile('test_rasp_lines.txt').toPath()

    when:
    TestFilesSuite.linesDefaultCharset(path).close()

    then:
    1 * helper.beforeFileLoaded(path.toString())
  }
}
