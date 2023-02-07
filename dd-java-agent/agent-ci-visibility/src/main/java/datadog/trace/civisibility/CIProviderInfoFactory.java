package datadog.trace.civisibility;

public class CIProviderInfoFactory {

  public static CIProviderInfo createCIProviderInfo() {
    // CI and Git information is obtained
    // from different environment variables
    // depending on which CI server is running the build.
    if (System.getenv(JenkinsInfo.JENKINS) != null) {
      return new JenkinsInfo();
    } else if (System.getenv(GitLabInfo.GITLAB) != null) {
      return new GitLabInfo();
    } else if (System.getenv(TravisInfo.TRAVIS) != null) {
      return new TravisInfo();
    } else if (System.getenv(CircleCIInfo.CIRCLECI) != null) {
      return new CircleCIInfo();
    } else if (System.getenv(AppVeyorInfo.APPVEYOR) != null) {
      return new AppVeyorInfo();
    } else if (System.getenv(AzurePipelinesInfo.AZURE) != null) {
      return new AzurePipelinesInfo();
    } else if (System.getenv(BitBucketInfo.BITBUCKET) != null) {
      return new BitBucketInfo();
    } else if (System.getenv(GithubActionsInfo.GHACTIONS) != null) {
      return new GithubActionsInfo();
    } else if (System.getenv(BuildkiteInfo.BUILDKITE) != null) {
      return new BuildkiteInfo();
    } else if (System.getenv(BitriseInfo.BITRISE) != null) {
      return new BitriseInfo();
    } else if (System.getenv(BuddyInfo.BUDDY) != null) {
      return new BuddyInfo();
    } else {
      return new UnknownCIInfo();
    }
  }
}
