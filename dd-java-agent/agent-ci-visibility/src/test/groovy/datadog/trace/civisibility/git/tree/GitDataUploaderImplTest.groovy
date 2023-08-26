package datadog.trace.civisibility.git.tree

import datadog.trace.api.Config
import datadog.trace.api.git.GitInfo
import datadog.trace.api.git.GitInfoProvider
import datadog.trace.civisibility.utils.IOUtils
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class GitDataUploaderImplTest extends Specification {

  private static final long TIMEOUT_MILLIS = 15_000

  @TempDir
  private Path tempDir

  def "test git data uploading"() {
    given:
    givenGitRepo()

    def repoRoot = tempDir.toString()
    def repoUrl = "<mockRepositoryUrl>"

    def config = Stub(Config) {
      getCiVisibilityGitUploadTimeoutMillis() >> 15_000
    }
    def api = Mock(GitDataApi)
    def gitInfoProvider = Stub(GitInfoProvider)
    gitInfoProvider.getGitInfo(repoRoot) >> new GitInfo(repoUrl, null, null, null)

    def gitClient = new GitClient(repoRoot, "25 years ago", 3, TIMEOUT_MILLIS)
    def uploader = new GitDataUploaderImpl(config, api, gitClient, gitInfoProvider, repoRoot, "origin")

    when:
    def future = uploader.startOrObserveGitDataUpload()
    future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

    then:
    1 * api.searchCommits(repoUrl, [
      "5b6f3a6dab5972d73a56dff737bd08d995255c08",
      "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c",
      "31ca182c0474f6265e660498c4fbcf775e23bba0",
    ]) >> [
      "98cd7c8e9cf71e02dc28bd9b13928bee0f85b74c",
      "31ca182c0474f6265e660498c4fbcf775e23bba0",
    ]

    1 * api.uploadPackFile(
      repoUrl,
      "5b6f3a6dab5972d73a56dff737bd08d995255c08",
      fileWithContents(Paths.get(getClass().getClassLoader().getResource("ci/git/uploadedPackFile.pack").toURI())))
    0 * _
  }

  private void givenGitRepo() {
    def gitFolder = Paths.get(getClass().getClassLoader().getResource("ci/git/with_pack/git").toURI())
    def tempGitFolder = tempDir.resolve(".git")
    Files.createDirectories(tempGitFolder)
    IOUtils.copyFolder(gitFolder, tempGitFolder)
  }

  private Matcher<Path> fileWithContents(Path expected) {
    def expectedContents = Files.readAllBytes(expected)
    return new FileWithContents(expectedContents)
  }

  private static final class FileWithContents extends TypeSafeMatcher<Path> {
    private final byte[] expectedContents

    private FileWithContents(byte[] expectedContents) {
      this.expectedContents = expectedContents
    }

    @Override
    protected boolean matchesSafely(Path path) {
      def contents = Files.readAllBytes(path)
      return Arrays.equals(contents, this.expectedContents)
    }

    @Override
    void describeTo(Description description) {
      description.appendText("path pointing to file with expected contents")
    }
  }
}
