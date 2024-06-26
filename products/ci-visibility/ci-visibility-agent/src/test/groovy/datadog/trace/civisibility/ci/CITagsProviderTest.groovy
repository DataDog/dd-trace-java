package datadog.trace.civisibility.ci

import datadog.trace.api.Config
import datadog.trace.api.git.GitInfo
import datadog.trace.api.git.GitInfoProvider
import datadog.trace.api.git.UserSuppliedGitInfoBuilder
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.git.CILocalGitInfoBuilder
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder
import datadog.trace.civisibility.git.tree.GitClient
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import org.junit.contrib.java.lang.system.RestoreSystemProperties
import spock.lang.Specification

import java.nio.file.Path
import java.nio.file.Paths

abstract class CITagsProviderTest extends Specification {

  static final CI_WORKSPACE_PATH_FOR_TESTS = "ci/ci_workspace_for_tests"
  static final GIT_FOLDER_FOR_TESTS = "git_folder_for_tests"

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
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), GIT_FOLDER_FOR_TESTS)
    def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(getWorkspacePath())
    def ciInfo = ciProviderInfo.buildCIInfo()
    def ciTagsProvider = ciTagsProvider()
    def tags = ciTagsProvider.getCiTags(ciInfo)

    then:
    if (isCi()) {
      def tagMismatches = ciSpec.getTagMismatches(tags)
      assert tagMismatches.isEmpty()
    }

    where:
    ciSpec << CISpecExtractor.extract(getProviderName())
  }

  def "test user supplied commit hash takes precedence over auto-detected git info"() {
    setup:
    buildRemoteGitInfoEmpty().each {
      environmentVariables.set(it.key, it.value)
    }

    environmentVariables.set(GitInfo.DD_GIT_COMMIT_SHA, "1234567890123456789012345678901234567890")

    when:
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), GIT_FOLDER_FOR_TESTS)
    def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(getWorkspacePath())
    def ciInfo = ciProviderInfo.buildCIInfo()
    def ciTagsProvider = ciTagsProvider()
    def tags = ciTagsProvider.getCiTags(ciInfo)

    then:
    tags.get(Tags.GIT_COMMIT_SHA) == "1234567890123456789012345678901234567890"
  }

  def "test user supplied repo url takes precedence over auto-detected git info"() {
    setup:
    buildRemoteGitInfoEmpty().each {
      environmentVariables.set(it.key, it.value)
    }

    environmentVariables.set(GitInfo.DD_GIT_REPOSITORY_URL, "local supplied repo url")

    when:
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), GIT_FOLDER_FOR_TESTS)
    def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(getWorkspacePath())
    def ciInfo = ciProviderInfo.buildCIInfo()
    def ciTagsProvider = ciTagsProvider()
    def tags = ciTagsProvider.getCiTags(ciInfo)

    then:
    tags.get(Tags.GIT_REPOSITORY_URL) == "local supplied repo url"
  }

  def "test set local git info if remote git info is not present"() {
    setup:
    buildRemoteGitInfoEmpty().each {
      environmentVariables.set(it.key, it.value)
    }

    when:
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), GIT_FOLDER_FOR_TESTS)
    def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(getWorkspacePath())
    def ciInfo = ciProviderInfo.buildCIInfo()
    def ciTagsProvider = ciTagsProvider()
    def tags = ciTagsProvider.getCiTags(ciInfo)

    then:
    if (isWorkspaceAwareCi()) {
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
    def ciTagsProvider = ciTagsProvider()

    then:
    if (isCi()) {
      CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), GIT_FOLDER_FOR_TESTS)
      def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(getWorkspacePath())
      def ciInfo = ciProviderInfo.buildCIInfo()
      def tags = ciTagsProvider.getCiTags(ciInfo)

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

  abstract String getProviderName()

  Map<String, String> buildRemoteGitInfoEmpty() {
    return new HashMap<String, String>()
  }

  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    return new HashMap<String, String>()
  }

  CITagsProvider ciTagsProvider() {
    GitClient.Factory gitClientFactory = Stub(GitClient.Factory)
    gitClientFactory.create(_) >> Stub(GitClient)

    GitInfoProvider gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(new UserSuppliedGitInfoBuilder())
    gitInfoProvider.registerGitInfoBuilder(new CIProviderGitInfoBuilder())
    gitInfoProvider.registerGitInfoBuilder(new CILocalGitInfoBuilder(gitClientFactory, GIT_FOLDER_FOR_TESTS))
    return new CITagsProvider(gitInfoProvider)
  }

  def "resolve"(workspace) {
    def resolvedWS = Paths.get(getClass().getClassLoader().getResource(workspace).toURI()).toFile().getAbsolutePath()
    return resolvedWS
  }

  Path getWorkspacePath() {
    return Paths.get(localFSGitWorkspace)
  }

  boolean isCi() {
    true
  }

  boolean isWorkspaceAwareCi() {
    true
  }
}
