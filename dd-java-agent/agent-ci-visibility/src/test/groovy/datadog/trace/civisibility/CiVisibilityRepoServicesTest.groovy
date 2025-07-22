package datadog.trace.civisibility

import datadog.trace.api.Config
import datadog.trace.api.git.CommitInfo
import datadog.trace.api.git.PersonInfo
import datadog.trace.civisibility.ci.CIProviderInfo
import datadog.trace.civisibility.ci.PullRequestInfo
import datadog.trace.civisibility.ci.env.CiEnvironment
import datadog.trace.civisibility.git.tree.GitClient
import datadog.trace.civisibility.git.tree.GitRepoUnshallow
import java.nio.file.Paths
import spock.lang.Specification

class CiVisibilityRepoServicesTest extends Specification {

  def "test get parent module name: #parentModuleName, #repoSubFolder, #serviceName"() {
    given:
    def config = Stub(Config)
    config.getCiVisibilityModuleName() >> parentModuleName
    config.getServiceName() >> serviceName

    def repoRoot = "/path/to/repo/root/"
    def path = Paths.get(repoRoot + repoSubFolder)

    expect:
    CiVisibilityRepoServices.getModuleName(config, repoRoot, path) == moduleName

    where:
    parentModuleName | repoSubFolder  | serviceName    | moduleName
    "parent-module"  | "child-module" | "service-name" | "parent-module"
    null             | "child-module" | "service-name" | "child-module"
    null             | ""             | "service-name" | "service-name"
  }

  def "test build PR info"() {
    setup:
    def expectedInfo = new PullRequestInfo(
      "master",
      "baseSha",
      new CommitInfo(
      "sourceSha",
      new PersonInfo("john", "john@doe.com", "never"),
      PersonInfo.NOOP,
      "hello world!"
      ),
      "42"
      )

    def config = Stub(Config)
    config.getGitPullRequestBaseBranch() >> expectedInfo.getPullRequestBaseBranch()

    def environment = Stub(CiEnvironment)
    environment.get(Constants.DDCI_PULL_REQUEST_TARGET_SHA) >> "targetSha"
    environment.get(Constants.DDCI_PULL_REQUEST_SOURCE_SHA) >> expectedInfo.getHeadCommit().getSha()

    def repoUnshallow = Stub(GitRepoUnshallow)
    def ciProviderInfo = Stub(CIProviderInfo)
    ciProviderInfo.buildPullRequestInfo() >> new PullRequestInfo(null, null, CommitInfo.NOOP, expectedInfo.getPullRequestNumber())

    def gitClient = Stub(GitClient)
    gitClient.getMergeBase("targetSha", "sourceSha") >> expectedInfo.getPullRequestBaseBranchSha()
    gitClient.getCommitInfo("sourceSha") >> expectedInfo.getHeadCommit()

    expect:
    CiVisibilityRepoServices.buildPullRequestInfo(config, environment, ciProviderInfo, gitClient, repoUnshallow) == expectedInfo
  }
}
