package datadog.trace.civisibility.ci


import spock.lang.Specification

class CIInfoTest extends Specification {

  def "test ci workspace is correctly sanitized #iterationIndex"() {
    def builder = CIInfo.builder(null)
    builder.ciWorkspace(workspacePath)
    def info = builder.build()

    info.ciWorkspace == sanitizedPath

    where:
    workspacePath | sanitizedPath
    null          | null
    "/"           | "/"
    "/repo/path"  | "/repo/path"
    "/repo/path/" | "/repo/path"
  }
}
