package datadog.trace.civisibility.ci

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
}
