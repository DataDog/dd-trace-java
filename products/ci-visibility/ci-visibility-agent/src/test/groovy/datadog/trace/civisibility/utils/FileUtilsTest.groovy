package datadog.trace.civisibility.utils

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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

  static workspace = resolve("ci/utils/workspace")
  static innerWorkspace = resolve("ci/utils/workspace/innerworkspace")

  def "test find path backwards "() {
    when:
    def result = FileUtils.findParentPathBackwards(path, target, isDirectory)

    then:
    result == expectedResult

    where:
    path | target | isDirectory | expectedResult
    null | null | false | null
    workspace | null | false | null
    workspace | "" | false | null
    workspace | "not-exists" | true | null
    workspace | "targetFolder" | false | null
    workspace | "targetFolder" | true | workspace
    workspace | "targetFile.txt" | false | workspace
    workspace | "targetFile.txt" | true | null
    innerWorkspace | "targetFolder" | true | workspace
    innerWorkspace | "targetFolder" | false | null
    innerWorkspace | "targetFile.txt" | true |null
    innerWorkspace | "targetFile.txt" | false | workspace
    innerWorkspace | "otherTargetFolder" | true | workspace
  }

  static "resolve"(workspace) {
    def resolvedWS = Paths.get(FileUtilsTest.getClassLoader().getResource(workspace).toURI())
    println(resolvedWS.toString())
    return resolvedWS
  }
}
