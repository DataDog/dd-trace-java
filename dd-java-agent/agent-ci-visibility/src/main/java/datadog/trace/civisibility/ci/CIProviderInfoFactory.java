package datadog.trace.civisibility.ci;

import datadog.trace.api.civisibility.ci.CIProviderInfo;
import java.nio.file.Path;

public class CIProviderInfoFactory {

  private final String targetFolder;

  public CIProviderInfoFactory() {
    this(".git");
  }

  CIProviderInfoFactory(String targetFolder) {
    this.targetFolder = targetFolder;
  }

  public CIProviderInfo createCIProviderInfo(Path currentPath) {
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
    } else if (System.getenv(CodefreshInfo.CODEFRESH) != null) {
      return new CodefreshInfo();
    } else if (System.getenv(TeamcityInfo.TEAMCITY) != null) {
      return new TeamcityInfo();
    } else {
      return new UnknownCIInfo(targetFolder, currentPath);
    }
  }
}
