package datadog.trace.bootstrap.instrumentation.ci

class BuildkiteInfoTest extends CIProviderInfoTest {

  @Override
  CIProviderInfo instanceProvider() {
    return new BuildkiteInfo() {
        @Override
        protected String getGitFolderName() {
          return GIT_FOLDER_FOR_TESTS
        }
      }
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
