package datadog.trace.civisibility.ci

class JenkinsInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return JenkinsInfo.JENKINS_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(JenkinsInfo.JENKINS, "true")
    map.put(JenkinsInfo.JENKINS_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(JenkinsInfo.JENKINS, "true")
    map.put(JenkinsInfo.JENKINS_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(JenkinsInfo.JENKINS_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }
}
