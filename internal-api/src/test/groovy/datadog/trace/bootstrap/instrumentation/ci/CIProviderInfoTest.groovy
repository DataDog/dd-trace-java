package datadog.trace.bootstrap.instrumentation.ci

import datadog.trace.bootstrap.instrumentation.api.Tags
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.contrib.java.lang.system.RestoreSystemProperties
import spock.lang.Specification

import java.nio.file.Paths

import static AppVeyorInfo.APPVEYOR
import static AzurePipelinesInfo.AZURE
import static BitBucketInfo.BITBUCKET
import static BitriseInfo.BITRISE
import static BuildkiteInfo.BUILDKITE
import static CIProviderInfo.selectCI
import static CircleCIInfo.CIRCLECI
import static GitLabInfo.GITLAB
import static GithubActionsInfo.GHACTIONS
import static JenkinsInfo.JENKINS
import static TravisInfo.TRAVIS

abstract class CIProviderInfoTest extends Specification {

  protected static final CI_WORKSPACE_PATH_FOR_TESTS = "ci/ci_workspace_for_tests"
  public static final GIT_FOLDER_FOR_TESTS = "git_folder_for_tests"

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables()

  @Rule
  public final RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties()

  protected final String localFSGitWorkspace = resolve(CI_WORKSPACE_PATH_FOR_TESTS)

  def setup() {
    // Clear all environment variables to avoid clashes between
    // real CI/Git environment variables and the spec CI/Git
    // environment variables.
    environmentVariables.clear(System.getenv().keySet() as String[])
  }

  def "test ci provider info is set properly"() {
    setup:
    ciSpec.env.each {
      environmentVariables.set(it.key, it.value)
      if (it.key == "HOME") {
        System.setProperty("user.home", it.value)
      }
    }

    when:
    def ciInfo = instanceProvider()

    then:
    if (ciInfo.CI) {
      assert ciSpec.assertTags(ciInfo.ciTags)
    }

    where:
    ciSpec << CISpecExtractor.extract(getProviderName())
  }

  def "test set local git info if remote git info is not present"() {
    setup:
    buildRemoteGitInfoEmpty().each {
      environmentVariables.set(it.key, it.value)
    }

    when:
    def ciInfo = instanceProvider()

    then:
    if (ciInfo.class != UnknownCIInfo) {
      def tags = ciInfo.ciTags
      tags.get(Tags.GIT_REPOSITORY_URL) == "https://some-host/some-user/some-repo.git"
      tags.get(Tags.GIT_BRANCH) == "master"
      tags.get(Tags.GIT_COMMIT_SHA) == "0797c248e019314fc1d91a483e859b32f4509953"
      tags.get(Tags.GIT_COMMIT_AUTHOR_NAME) == "John Doe"
      tags.get(Tags.GIT_COMMIT_AUTHOR_EMAIL) == "john@doe.com"
      tags.get(Tags.GIT_COMMIT_AUTHOR_DATE) == "2021-02-12T13:47:48.000Z"
      tags.get(Tags.GIT_COMMIT_COMMITTER_NAME) == "Jane Doe"
      tags.get(Tags.GIT_COMMIT_COMMITTER_EMAIL) == "jane@doe.com"
      tags.get(Tags.GIT_COMMIT_COMMITTER_DATE) == "2021-02-12T13:48:44.000Z"
      tags.get(Tags.GIT_COMMIT_MESSAGE) == "This is a commit message\n"
    }
  }

  def "test avoid setting local git info if remote commit does not match"() {
    setup:
    buildRemoteGitInfoMismatchLocalGit().each {
      environmentVariables.set(it.key, it.value)
    }

    when:
    def ciInfo = instanceProvider()

    then:
    if (ciInfo.class != UnknownCIInfo) {
      def tags = ciInfo.ciTags
      tags.get(Tags.GIT_REPOSITORY_URL) == "https://some-host/some-user/some-repo.git"
      tags.get(Tags.GIT_BRANCH) == "master"
      tags.get(Tags.GIT_COMMIT_SHA) == "0000000000000000000000000000000000000000"
      !tags.get(Tags.GIT_COMMIT_AUTHOR_NAME)
      !tags.get(Tags.GIT_COMMIT_AUTHOR_EMAIL)
      !tags.get(Tags.GIT_COMMIT_AUTHOR_DATE)
      !tags.get(Tags.GIT_COMMIT_COMMITTER_NAME)
      !tags.get(Tags.GIT_COMMIT_COMMITTER_EMAIL)
      !tags.get(Tags.GIT_COMMIT_COMMITTER_DATE)
      !tags.get(Tags.GIT_COMMIT_MESSAGE)
    }
  }

  def "test correct info is selected"() {
    setup:
    environmentVariables.set(ciKeySelector, "true")

    when:
    def ciInfo = selectCI()

    then:
    ciInfo.class == ciInfoClass

    where:
    ciKeySelector | ciInfoClass
    JENKINS       | JenkinsInfo
    GITLAB        | GitLabInfo
    TRAVIS        | TravisInfo
    CIRCLECI      | CircleCIInfo
    APPVEYOR      | AppVeyorInfo
    AZURE         | AzurePipelinesInfo
    GHACTIONS     | GithubActionsInfo
    BITBUCKET     | BitBucketInfo
    BUILDKITE     | BuildkiteInfo
    BITRISE       | BitriseInfo
    "none"        | UnknownCIInfo
  }

  abstract CIProviderInfo instanceProvider()

  abstract String getProviderName()

  Map<String, String> buildRemoteGitInfoEmpty() {
    return new HashMap<String, String>()
  }

  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    return new HashMap<String, String>()
  }

  def "resolve"(workspace) {
    def resolvedWS = Paths.get(getClass().getClassLoader().getResource(workspace).toURI()).toFile().getAbsolutePath()
    return resolvedWS
  }
}
