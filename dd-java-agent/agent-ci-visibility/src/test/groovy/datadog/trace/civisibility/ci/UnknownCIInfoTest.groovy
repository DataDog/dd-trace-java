package datadog.trace.civisibility.ci

import datadog.trace.api.Config
import datadog.trace.api.git.GitInfoProvider
import datadog.trace.api.git.UserSuppliedGitInfoBuilder
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl
import datadog.trace.civisibility.git.CILocalGitInfoBuilder
import datadog.trace.civisibility.git.CIProviderGitInfoBuilder
import datadog.trace.civisibility.git.tree.GitClient

import java.nio.file.Paths

class UnknownCIInfoTest extends CITagsProviderTest {

  def workspaceForTests = Paths.get(getClass().getClassLoader().getResource(CI_WORKSPACE_PATH_FOR_TESTS).toURI())

  @Override
  String getProviderName() {
    return UnknownCIInfo.UNKNOWN_PROVIDER_NAME
  }

  Map<String, String> buildRemoteGitInfoEmpty() {
    return new HashMap<String, String>()
  }

  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    return new HashMap<String, String>()
  }

  def "test info is set properly from local workspace"() {
    setup:
    def expectedTags = [
      (Tags.CI_WORKSPACE_PATH)         : "${workspaceForTests.toString()}",
      (Tags.GIT_REPOSITORY_URL)        : "https://some-host/some-user/some-repo.git",
      (Tags.GIT_BRANCH)                : "master",
      (Tags.GIT_COMMIT_SHA)            : "0797c248e019314fc1d91a483e859b32f4509953",
      (Tags.GIT_COMMIT_MESSAGE)        : "This is a commit message\n",
      (Tags.GIT_COMMIT_AUTHOR_NAME)    : "John Doe",
      (Tags.GIT_COMMIT_AUTHOR_EMAIL)   : "john@doe.com",
      (Tags.GIT_COMMIT_AUTHOR_DATE)    : "2021-02-12T13:47:48.000Z",
      (Tags.GIT_COMMIT_COMMITTER_NAME) : "Jane Doe",
      (Tags.GIT_COMMIT_COMMITTER_EMAIL): "jane@doe.com",
      (Tags.GIT_COMMIT_COMMITTER_DATE) : "2021-02-12T13:48:44.000Z"
    ]

    when:
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), GIT_FOLDER_FOR_TESTS, new CiEnvironmentImpl(env.getAll()))
    def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(workspaceForTests)
    def ciInfo = ciProviderInfo.buildCIInfo()
    def ciTagsProvider = ciTagsProvider()
    def ciTags = ciTagsProvider.getCiTags(ciInfo, PullRequestInfo.EMPTY)

    then:
    ciTags == expectedTags
  }

  def "test workspace is null if target folder does not exist"() {
    when:
    def gitClientFactory = Stub(GitClient.Factory)
    gitClientFactory.create(_) >> Stub(GitClient)

    GitInfoProvider gitInfoProvider = new GitInfoProvider()
    gitInfoProvider.registerGitInfoBuilder(new UserSuppliedGitInfoBuilder())
    gitInfoProvider.registerGitInfoBuilder(new CIProviderGitInfoBuilder(Config.get(), new CiEnvironmentImpl(env.getAll())))
    gitInfoProvider.registerGitInfoBuilder(new CILocalGitInfoBuilder(gitClientFactory, "this-target-folder-does-not-exist"))
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get(), "this-target-folder-does-not-exist", new CiEnvironmentImpl(env.getAll()))

    def ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(workspaceForTests)
    def ciInfo = ciProviderInfo.buildCIInfo()
    def ciTagsProvider = new CITagsProvider(gitInfoProvider)
    def ciTags = ciTagsProvider.getCiTags(ciInfo, PullRequestInfo.EMPTY)

    then:
    ciTags.get("$Tags.CI_WORKSPACE_PATH") == null
  }

  boolean isCi() {
    false
  }
}
