package datadog.trace.bootstrap.instrumentation.ci.git.pack

import datadog.trace.test.util.DDSpecification

import java.nio.file.Paths

class GitPackUtilsTest extends DDSpecification {

  def "test extract correct git pack version"() {
    when:
    def version = GitPackUtils.extractGitPackVersion(idxFile)

    then:
    version == (short) expectedVersion

    where:
    idxFile                                  | expectedVersion
    file("ci/git/pack/utils/version/v1.idx") | 1
    file("ci/git/pack/utils/version/v2.idx") | 2
  }

  def "test return correct pack filename based on idx file"() {
    when:
    def packFile = GitPackUtils.getPackFile(idxFile)

    then:
    packFile.name == expectedPackFilename

    where:
    idxFile                                  | expectedPackFilename
    file("ci/git/pack/utils/version/v2.idx") | "v2.pack"
  }

  def "test read bytes correctly"() {
    setup:
    def raFile = new RandomAccessFile(file, "r")

    when:
    def bArray = GitPackUtils.readBytes(raFile, 4)

    then:
    bArray == expectedBArray

    where:
    file                                     | expectedBArray
    file("ci/git/pack/utils/version/v2.idx") | GitPackUtils.HEADER
  }

  def "test convert hex to byte array correctly"() {
    setup:
    def expectedBArray = [1, 35, 69, 103, -119, -85, -51, -17] as byte[]

    when:
    def bArray = GitPackUtils.hexToByteArray("0123456789abcdef")

    then:
    bArray == expectedBArray
  }

  def "file"(filepath) {
    return Paths.get(getClass().getClassLoader().getResource(filepath).toURI()).toFile()
  }
}
