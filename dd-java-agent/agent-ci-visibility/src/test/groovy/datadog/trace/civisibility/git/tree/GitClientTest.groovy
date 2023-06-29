package datadog.trace.civisibility.git.tree

import datadog.trace.civisibility.git.GitObject
import datadog.trace.civisibility.git.pack.V2PackGitInfoExtractor
import datadog.trace.civisibility.utils.IOUtils
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GitClientTest extends Specification {

  private static final int GIT_COMMAND_TIMEOUT_MILLIS = 10_000

  @TempDir
  private Path tempDir

  def "test is not shallow"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def shallow = gitClient.isShallow()

    then:
    !shallow
  }

  def "test is shallow"() {
    given:
    givenGitRepo("ci/git/shallow/git")

    when:
    def gitClient = givenGitClient()
    def shallow = gitClient.isShallow()

    then:
    shallow
  }

  def "test unshallow"() {
    given:
    givenGitRepo("ci/git/shallow/git")

    when:
    def gitClient = givenGitClient()
    def shallow = gitClient.isShallow()
    def commits = gitClient.getLatestCommits()

    then:
    shallow
    commits.size() == 1

    when:
    gitClient.unshallow()
    shallow = gitClient.isShallow()
    commits = gitClient.getLatestCommits()

    then:
    !shallow
    commits.size() == 10
  }

  def "test get remote url"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def remoteUrl = gitClient.getRemoteUrl("origin")

    then:
    remoteUrl == "git@github.com:DataDog/dd-trace-dotnet.git"
  }

  def "test get latest commits"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def commits = gitClient.getLatestCommits()

    then:
    commits == [
      "5b6f3a6dab5972d73a56dff737bd08d995255c08",
      "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c",
      "31ca182c0474f6265e660498c4fbcf775e23bba0",
      "1bd740dd476c38d4b4d706d3ad7cb59cd0b84f7d",
      "2b788c66fc4b58ce6ca7b94fbaf1b94a3ea3a93e",
      "15d5d8e09cbf369f2fa6929c0b0c74b2b0a22193",
      "6aaa4085c10d16b63a910043e35dbd35d2ef7f1c",
      "10599ae3c17d66d642f9f143b1ff3dd236111e2a",
      "5128e6f336cce5a431df68fa0ec42f8c8d0776b1",
      "0c623e9dab4349960930337c936bf9975456e82f"
    ]
  }

  def "test get objects"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def objects = gitClient.getObjects(["98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c"], ["5b6f3a6dab5972d73a56dff737bd08d995255c08"])

    then:
    objects == [
      "5b6f3a6dab5972d73a56dff737bd08d995255c08",
      "c52914110869ff3999bca4837410511f17787e87",
      "cd3407343e846f6707d34b77f38e86345063d0bf",
      "e7fff9f77d05daca86a6bbec334a3304da23278b",
      "a70ad1f15bda97e2f154a0ac6577e11d55ee05d3",
      "3d02ff4958a9ef00b36b1f6e755e3e4e9c92ba5f",
      "5ba3615fbe9ae3dd4338fae6f67f013c212f83b5",
      "fd408d6995f1651a245c227d57529bf8a51ffe45"
    ]
  }

  def "test create pack files"() {
    given:
    givenGitRepo()

    when:
    def gitClient = givenGitClient()
    def packFilesDir = gitClient.createPackFiles([
      "5b6f3a6dab5972d73a56dff737bd08d995255c08",
      "c52914110869ff3999bca4837410511f17787e87",
      "cd3407343e846f6707d34b77f38e86345063d0bf",
      "e7fff9f77d05daca86a6bbec334a3304da23278b",
      "a70ad1f15bda97e2f154a0ac6577e11d55ee05d3",
      "3d02ff4958a9ef00b36b1f6e755e3e4e9c92ba5f",
      "5ba3615fbe9ae3dd4338fae6f67f013c212f83b5",
      "fd408d6995f1651a245c227d57529bf8a51ffe45"
    ])

    then:
    def indexFile = Files.list(packFilesDir).filter { it.toString().endsWith(".idx") }.iterator().next().toFile()
    def packFile = Files.list(packFilesDir).filter { it.toString().endsWith(".pack") }.iterator().next().toFile()

    def extractor = new V2PackGitInfoExtractor()
    def gitPackObject = extractor.extract(indexFile, packFile, "5b6f3a6dab5972d73a56dff737bd08d995255c08")

    !gitPackObject.raisedError()
    gitPackObject.getType() == GitObject.COMMIT_TYPE
  }

  private void givenGitRepo() {
    givenGitRepo("ci/git/with_pack/git")
  }

  private void givenGitRepo(String resourceName) {
    def gitFolder = Paths.get(getClass().getClassLoader().getResource(resourceName).toURI())
    def tempGitFolder = tempDir.resolve(".git")
    Files.createDirectories(tempGitFolder)
    IOUtils.copyFolder(gitFolder, tempGitFolder)
  }

  private givenGitClient() {
    new GitClient(tempDir.toString(), "25 years ago", 10, GIT_COMMAND_TIMEOUT_MILLIS)
  }
}
