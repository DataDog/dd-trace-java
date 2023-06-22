package datadog.trace.civisibility.ci

class TravisInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return TravisInfo.TRAVIS_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(TravisInfo.TRAVIS, "true")
    map.put(TravisInfo.TRAVIS_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(TravisInfo.TRAVIS, "true")
    map.put(TravisInfo.TRAVIS_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(TravisInfo.TRAVIS_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }
}
