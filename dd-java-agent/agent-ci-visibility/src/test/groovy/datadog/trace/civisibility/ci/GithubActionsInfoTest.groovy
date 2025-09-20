package datadog.trace.civisibility.ci

import datadog.trace.civisibility.ci.env.CiEnvironmentImpl

import java.nio.file.Paths

class GithubActionsInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return GithubActionsInfo.GHACTIONS_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(GithubActionsInfo.GHACTIONS, "true")
    map.put(GithubActionsInfo.GHACTIONS_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(GithubActionsInfo.GHACTIONS, "true")
    map.put(GithubActionsInfo.GHACTIONS_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(GithubActionsInfo.GHACTIONS_SHA, "0000000000000000000000000000000000000000")
    return map
  }

  def "test pull request info parsing"() {
    setup:
    def githubEvent = GithubActionsInfoTest.getResource("/ci/github-event.json")
    def githubEventPath = Paths.get(githubEvent.toURI())

    env.set(GithubActionsInfo.GITHUB_BASE_REF, "base-ref")
    env.set(GithubActionsInfo.GITHUB_EVENT_PATH, githubEventPath.toString())

    when:
    def pullRequestInfo = new GithubActionsInfo(new CiEnvironmentImpl(env.getAll())).buildPullRequestInfo()

    then:
    pullRequestInfo.getBaseBranch() == "base-ref"
    pullRequestInfo.getBaseBranchSha() == null
    pullRequestInfo.getBaseBranchHeadSha() == "52e0974c74d41160a03d59ddc73bb9f5adab054b"
    pullRequestInfo.getHeadCommit().getSha() == "df289512a51123083a8e6931dd6f57bb3883d4c4"
    pullRequestInfo.getPullRequestNumber() == "1"
  }
}
