package datadog.trace.civisibility.ci

class DroneInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return DroneInfo.DRONE_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(DroneInfo.DRONE, "true")
    map.put(DroneInfo.DRONE_WORKSPACE, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(DroneInfo.DRONE, "true")
    map.put(DroneInfo.DRONE_WORKSPACE, localFSGitWorkspace)
    map.put(DroneInfo.DRONE_COMMIT_SHA, "0000000000000000000000000000000000000000")
    return map
  }
}
