package datadog.trace.civisibility.ci

class BuddyInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return BuddyInfo.BUDDY_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(BuddyInfo.BUDDY, "true")
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(BuddyInfo.BUDDY, "true")
    map.put(BuddyInfo.BUDDY_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }

  @Override
  void setupPullRequestInfoBuild() {
    environmentVariables.set(BuddyInfo.BUDDY_RUN_PR_BASE_BRANCH, "base-branch")
  }

  @Override
  PullRequestInfo expectedPullRequestInfo() {
    return new PullRequestInfo(
      "base-branch",
      null,
      null
      )
  }

  boolean isWorkspaceAwareCi() {
    false
  }
}
