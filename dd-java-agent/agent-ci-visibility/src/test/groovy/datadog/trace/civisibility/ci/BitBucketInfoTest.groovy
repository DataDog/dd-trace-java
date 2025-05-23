package datadog.trace.civisibility.ci

class BitBucketInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return BitBucketInfo.BITBUCKET_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(BitBucketInfo.BITBUCKET, "true")
    map.put(BitBucketInfo.BITBUCKET_WORKSPACE_PATH, localFSGitWorkspace)
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(BitBucketInfo.BITBUCKET, "true")
    map.put(BitBucketInfo.BITBUCKET_WORKSPACE_PATH, localFSGitWorkspace)
    map.put(BitBucketInfo.BITBUCKET_GIT_COMMIT, "0000000000000000000000000000000000000000")
    return map
  }

  @Override
  void setupPullRequestInfoBuild() {
    environmentVariables.set(BitBucketInfo.BITBUCKET_PR_DESTINATION_BRANCH, "base-branch")
  }

  @Override
  PullRequestInfo expectedPullRequestInfo() {
    return new PullRequestInfo(
      "base-branch",
      null,
      null,
      )
  }
}
