package datadog.trace.civisibility.ci

class GitLabInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return GitLabInfo.GITLAB_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(GitLabInfo.GITLAB, "true")
    map.put(GitLabInfo.GITLAB_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(GitLabInfo.GITLAB, "true")
    map.put(GitLabInfo.GITLAB_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(GitLabInfo.GITLAB_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }

  @Override
  void setupPullRequestInfoBuild() {
    environmentVariables.set(GitLabInfo.GITLAB_PULL_REQUEST_BASE_BRANCH, "base-branch")
    environmentVariables.set(GitLabInfo.GITLAB_PULL_REQUEST_BASE_BRANCH_SHA, "cab317e178b740e6cb651dd7486bd925338b2843")
    environmentVariables.set(GitLabInfo.GITLAB_PULL_REQUEST_COMMIT_HEAD_SHA, "b861e7fbb94b4949972b528ff7315c329e36092c")
  }

  @Override
  PullRequestInfo expectedPullRequestInfo() {
    return new PullRequestInfo(
      "base-branch",
      "cab317e178b740e6cb651dd7486bd925338b2843",
      "b861e7fbb94b4949972b528ff7315c329e36092c"
      )
  }
}
