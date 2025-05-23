package datadog.trace.civisibility.ci


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

  @Override
  void setupPullRequestInfoBuild() {
    def githubEvent = GithubActionsInfoTest.getResource("/ci/github-event.json")
    def githubEventPath = Paths.get(githubEvent.toURI())

    environmentVariables.set(GithubActionsInfo.GITHUB_BASE_REF, "base-ref")
    environmentVariables.set(GithubActionsInfo.GITHUB_EVENT_PATH, githubEventPath.toString())
  }

  @Override
  PullRequestInfo expectedPullRequestInfo() {
    return new PullRequestInfo(
      "base-ref",
      "52e0974c74d41160a03d59ddc73bb9f5adab054b",
      "df289512a51123083a8e6931dd6f57bb3883d4c4")
  }
}
