package datadog.trace.civisibility.ci

class BitriseInfoTest extends CITagsProviderTest {
  @Override
  String getProviderName() {
    return BitriseInfo.BITRISE_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(BitriseInfo.BITRISE, "true")
    map.put(BitriseInfo.BITRISE_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(BitriseInfo.BITRISE, "true")
    map.put(BitriseInfo.BITRISE_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(BitriseInfo.BITRISE_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }
}
