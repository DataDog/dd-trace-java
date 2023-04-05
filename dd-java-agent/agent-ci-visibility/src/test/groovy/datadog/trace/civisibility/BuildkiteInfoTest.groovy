package datadog.trace.civisibility

import datadog.trace.api.civisibility.CIProviderInfo

class BuildkiteInfoTest extends CITagsProviderImplTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new BuildkiteInfo()
  }

  @Override
  String getProviderName() {
    return BuildkiteInfo.BUILDKITE_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(BuildkiteInfo.BUILDKITE, "true")
    map.put(BuildkiteInfo.BUILDKITE_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(BuildkiteInfo.BUILDKITE, "true")
    map.put(BuildkiteInfo.BUILDKITE_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(BuildkiteInfo.BUILDKITE_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }
}
