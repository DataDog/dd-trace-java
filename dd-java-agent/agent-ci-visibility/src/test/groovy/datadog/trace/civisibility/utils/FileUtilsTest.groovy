package datadog.trace.civisibility.utils

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class FileUtilsTest extends Specification {

  @TempDir
  Path temporaryFolder

  def "test folder delete"() {
    given:
    Files.createFile(temporaryFolder.resolve("childFile"))
    Files.createDirectory(temporaryFolder.resolve("childFolder"))
    Files.createDirectory(temporaryFolder.resolve("childFolder").resolve("childFile"))

    when:
    FileUtils.delete(temporaryFolder)

    then:
    !Files.exists(temporaryFolder)
  }
}
