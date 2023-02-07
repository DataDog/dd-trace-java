package datadog.trace.civisibility

class AppVeyorInfoTest extends CITagsProviderImplTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new AppVeyorInfo()
  }

  @Override
  String getProviderName() {
    return AppVeyorInfo.APPVEYOR_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(AppVeyorInfo.APPVEYOR, "true")
    map.put(AppVeyorInfo.APPVEYOR_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(AppVeyorInfo.APPVEYOR, "true")
    map.put(AppVeyorInfo.APPVEYOR_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(AppVeyorInfo.APPVEYOR_REPO_PROVIDER, "github")
    map.put(AppVeyorInfo.APPVEYOR_REPO_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }
}
