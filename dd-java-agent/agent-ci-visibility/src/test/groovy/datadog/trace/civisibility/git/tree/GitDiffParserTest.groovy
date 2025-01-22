package datadog.trace.civisibility.git.tree

import spock.lang.Specification

import static datadog.trace.civisibility.TestUtils.lines

class GitDiffParserTest extends Specification {

  def "test git diff parsing: #filename"() {
    when:
    def diff
    try (def gitDiff = GitDiffParserTest.getResourceAsStream(filename)) {
      diff = GitDiffParser.parse(gitDiff)
    }

    then:
    diff.linesByRelativePath == result

    where:
    filename              | result
    "git-diff.txt"        | [
      "java/maven-junit4/pom.xml": lines(10),
      "java/maven-junit5/pom.xml": lines(14, 41),
    ]
    "larger-git-diff.txt" | [
      "java/maven-junit5/pom.xml"         : lines(14, 41),
      "java/maven-junit5/module-a/pom.xml": lines(8, 9, 10, 13, 14, 15, 20, 21, 22, 27, 28, 36, 40),
      "java/maven-junit5/module-b/pom.xml": lines(),
      "java/maven-junit4/pom.xml"         : lines(10)
    ]
  }
}
