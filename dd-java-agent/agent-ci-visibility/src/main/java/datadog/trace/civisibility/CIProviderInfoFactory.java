package datadog.trace.bootstrap.instrumentation.ci;

import static datadog.trace.bootstrap.instrumentation.ci.AppVeyorInfo.APPVEYOR;
import static datadog.trace.bootstrap.instrumentation.ci.AzurePipelinesInfo.AZURE;
import static datadog.trace.bootstrap.instrumentation.ci.BitBucketInfo.BITBUCKET;
import static datadog.trace.bootstrap.instrumentation.ci.BitriseInfo.BITRISE;
import static datadog.trace.bootstrap.instrumentation.ci.BuddyInfo.BUDDY;
import static datadog.trace.bootstrap.instrumentation.ci.BuildkiteInfo.BUILDKITE;
import static datadog.trace.bootstrap.instrumentation.ci.CircleCIInfo.CIRCLECI;
import static datadog.trace.bootstrap.instrumentation.ci.GitLabInfo.GITLAB;
import static datadog.trace.bootstrap.instrumentation.ci.GithubActionsInfo.GHACTIONS;
import static datadog.trace.bootstrap.instrumentation.ci.JenkinsInfo.JENKINS;
import static datadog.trace.bootstrap.instrumentation.ci.TravisInfo.TRAVIS;

public class CIProviderInfoFactory {

  public static CIProviderInfo createCIProviderInfo() {
    // CI and Git information is obtained
    // from different environment variables
    // depending on which CI server is running the build.
    if (System.getenv(JENKINS) != null) {
      return new JenkinsInfo();
    } else if (System.getenv(GITLAB) != null) {
      return new GitLabInfo();
    } else if (System.getenv(TRAVIS) != null) {
      return new TravisInfo();
    } else if (System.getenv(CIRCLECI) != null) {
      return new CircleCIInfo();
    } else if (System.getenv(APPVEYOR) != null) {
      return new AppVeyorInfo();
    } else if (System.getenv(AZURE) != null) {
      return new AzurePipelinesInfo();
    } else if (System.getenv(BITBUCKET) != null) {
      return new BitBucketInfo();
    } else if (System.getenv(GHACTIONS) != null) {
      return new GithubActionsInfo();
    } else if (System.getenv(BUILDKITE) != null) {
      return new BuildkiteInfo();
    } else if (System.getenv(BITRISE) != null) {
      return new BitriseInfo();
    } else if (System.getenv(BUDDY) != null) {
      return new BuddyInfo();
    } else {
      return new UnknownCIInfo();
    }
  }
}
