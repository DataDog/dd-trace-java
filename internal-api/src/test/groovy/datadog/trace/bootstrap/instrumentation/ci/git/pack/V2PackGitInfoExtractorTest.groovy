package datadog.trace.bootstrap.instrumentation.ci.git.pack

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import java.nio.file.Paths

import static datadog.trace.bootstrap.instrumentation.ci.git.GitObject.COMMIT_TYPE
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackObject.ERROR_PACK_OBJECT
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackObject.NOT_FOUND_PACK_OBJECT
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.GitPackUtils.seek
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.VersionedPackGitInfoExtractor.SIZE_INDEX
import static datadog.trace.bootstrap.instrumentation.ci.git.pack.VersionedPackGitInfoExtractor.TYPE_INDEX

class V2PackGitInfoExtractorTest extends DDSpecification {

  @Shared
  def sut = new V2PackGitInfoExtractor()

  def "test extract git info from packfiles v2"() {
    when:
    def gitPackObject = sut.extract(idxFile, packFile, commitSha)

    then:
    gitPackObject.type == expectedType
    GitPackTestHelper.inflate(gitPackObject.deflatedContent) == expectedContent

    where:
    idxFile                                                | packFile                                                | commitSha                                  | expectedType               | expectedContent
    file("ci/git/pack/extractor/v2/invalid-pack-size.idx") | file("ci/git/pack/extractor/v2/invalid-pack-size.pack") | "0000000000000000000000000000000000000000" | ERROR_PACK_OBJECT.type     | ERROR_PACK_OBJECT.deflatedContent
    file("ci/git/pack/extractor/v2/pack-v2.idx")           | file("ci/git/pack/extractor/v2/pack-v2.pack")           | "0000000000000000000000000000000000000000" | NOT_FOUND_PACK_OBJECT.type | NOT_FOUND_PACK_OBJECT.deflatedContent
    file("ci/git/pack/extractor/v2/pack-v2.idx")           | file("ci/git/pack/extractor/v2/pack-v2.pack")           | "5b6f3a6dab5972d73a56dff737bd08d995255c08" | COMMIT_TYPE                | GitPackTestHelper.content_5b6f3a6dab5972d73a56dff737bd08d995255c08()
  }

  def "test search correct sha index"() {
    setup:
    def idx = new RandomAccessFile(idxFile, "r")
    seek(idx, 8 + (256 * 4), GitPackUtils.SeekOrigin.BEGIN)

    when:
    def shaIndex = sut.searchSha(idx, commitSha, totalObjects, previousObjects, indexObjects)

    then:
    shaIndex == expectedShaIndex

    where:
    idxFile                                      | commitSha                                  | totalObjects | previousObjects | indexObjects | expectedShaIndex
    file("ci/git/pack/extractor/v2/pack-v2.idx") | "5b6f3a6dab5972d73a56dff737bd08d995255c08" | 28841        | 10310           | 126          | 10367
  }

  def "test search correct offset from idx file"() {
    setup:
    def idx = new RandomAccessFile(idxFile, "r")

    when:
    def offset = sut.searchOffset(idx, shaIndex, totalObjects)

    then:
    offset == expectedOffset

    where:
    idxFile                                                             | shaIndex | totalObjects | expectedOffset
    file("ci/git/pack/utils/offset/offset65535_pos4_total10.idx")       | 4        | 10           | 65535
    file("ci/git/pack/utils/offset/offset65535_pos4_total10_large.idx") | 4        | 10           | 65535
  }

  def "test extract correct git object size from pack file"() {
    setup:
    def pack = new RandomAccessFile(packFile, "r")

    when:
    def data = sut.extractGitObjectTypeAndSize(pack)

    then:
    data[TYPE_INDEX] == expectedType
    data[SIZE_INDEX] == expectedSize

    where:
    packFile                                        | expectedType | expectedSize
    file("ci/git/pack/utils/size/size1.pack")       | COMMIT_TYPE  | 1
    file("ci/git/pack/utils/size/size256.pack")     | COMMIT_TYPE  | 256
    file("ci/git/pack/utils/size/size860.pack")     | COMMIT_TYPE  | 860
    file("ci/git/pack/utils/size/sizeInvalid.pack") | -1           | -1
  }


  def "file"(filepath) {
    return Paths.get(getClass().getClassLoader().getResource(filepath).toURI()).toFile()
  }
}
