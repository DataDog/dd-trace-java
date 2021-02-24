package datadog.trace.bootstrap.instrumentation.ci

class GithubActionsInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new GithubActionsInfo() {
      @Override
      protected String getGitFolderName() {
        return GIT_FOLDER_FOR_TESTS;
      }
    }
  }

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
