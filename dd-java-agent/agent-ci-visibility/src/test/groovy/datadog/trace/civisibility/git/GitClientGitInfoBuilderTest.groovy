package datadog.trace.civisibility.git

import datadog.trace.api.Config
import datadog.trace.civisibility.git.tree.GitClient
import datadog.trace.civisibility.utils.IOUtils
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GitClientGitInfoBuilderTest extends Specification {

  private static final int GIT_COMMAND_TIMEOUT_MILLIS = 10_000

  private static final String GIT_FOLDER = ".git"

  @TempDir
  private Path tempDir

  def "test git client info builder"() {
    given:
    givenGitRepo()

    def config = Stub(Config)
    config.getCiVisibilityGitRemoteName() >> "origin"
    config.getCiVisibilityGitCommandTimeoutMillis() >> GIT_COMMAND_TIMEOUT_MILLIS

    def gitClientFactory = new GitClient.Factory(config)
    def infoBuilder = new GitClientGitInfoBuilder(config, gitClientFactory)

    when:
    def gitInfo = infoBuilder.build(tempDir.toAbsolutePath().toString())

    then:
    gitInfo.repositoryURL == "git@github.com:DataDog/dd-trace-dotnet.git"
    gitInfo.branch == "master"
    gitInfo.tag == null
    gitInfo.commit.sha == "5b6f3a6dab5972d73a56dff737bd08d995255c08"
    gitInfo.commit.author.name == "Tony Redondo"
    gitInfo.commit.author.email == "tony.redondo@datadoghq.com"
    gitInfo.commit.author.iso8601Date == "2021-02-26T19:32:13+01:00"
    gitInfo.commit.committer.name == "GitHub"
    gitInfo.commit.committer.email == "noreply@github.com"
    gitInfo.commit.committer.iso8601Date == "2021-02-26T19:32:13+01:00"
    gitInfo.commit.fullMessage == "Adding Git information to test spans (#1242)\n\n" +
      "* Initial basic GitInfo implementation.\r\n\r\n" +
      "* Adds Author, Committer and Message git parser.\r\n\r\n" +
      "* Changes based on the review."
  }

  private void givenGitRepo() {
    givenGitRepo("ci/git/with_pack/git")
  }

  private void givenGitRepo(String resourceName) {
    def gitFolder = Paths.get(getClass().getClassLoader().getResource(resourceName).toURI())
    def tempGitFolder = tempDir.resolve(GIT_FOLDER)
    Files.createDirectories(tempGitFolder)
    IOUtils.copyFolder(gitFolder, tempGitFolder)
  }
}
