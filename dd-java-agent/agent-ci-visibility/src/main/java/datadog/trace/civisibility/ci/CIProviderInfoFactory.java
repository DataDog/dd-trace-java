package datadog.trace.civisibility.ci;

import datadog.trace.api.Config;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import java.nio.file.Path;

public class CIProviderInfoFactory {

  private final String targetFolder;
  private final Config config;
  private final CiEnvironment environment;

  public CIProviderInfoFactory(Config config, CiEnvironment environment) {
    this(config, ".git", environment);
  }

  CIProviderInfoFactory(Config config, String targetFolder, CiEnvironment environment) {
    this.targetFolder = targetFolder;
    this.config = config;
    this.environment = environment;
  }

  public CIProviderInfo createCIProviderInfo(Path currentPath) {
    if (!config.isCiVisibilityCiProviderIntegrationEnabled()) {
      return new UnknownCIInfo(environment, targetFolder, currentPath);
    }

    // CI and Git information is obtained
    // from different environment variables
    // depending on which CI server is running the build.
    if (System.getenv(JenkinsInfo.JENKINS) != null) {
      return new JenkinsInfo(environment);
    } else if (System.getenv(GitLabInfo.GITLAB) != null) {
      return new GitLabInfo(environment);
    } else if (System.getenv(TravisInfo.TRAVIS) != null) {
      return new TravisInfo(environment);
    } else if (System.getenv(CircleCIInfo.CIRCLECI) != null) {
      return new CircleCIInfo(environment);
    } else if (System.getenv(AppVeyorInfo.APPVEYOR) != null) {
      return new AppVeyorInfo(environment);
    } else if (System.getenv(AzurePipelinesInfo.AZURE) != null) {
      return new AzurePipelinesInfo(environment);
    } else if (System.getenv(BitBucketInfo.BITBUCKET) != null) {
      return new BitBucketInfo(environment);
    } else if (System.getenv(GithubActionsInfo.GHACTIONS) != null) {
      return new GithubActionsInfo(environment);
    } else if (System.getenv(BuildkiteInfo.BUILDKITE) != null) {
      return new BuildkiteInfo(environment);
    } else if (System.getenv(BitriseInfo.BITRISE) != null) {
      return new BitriseInfo(environment);
    } else if (System.getenv(BuddyInfo.BUDDY) != null) {
      return new BuddyInfo(environment);
    } else if (System.getenv(CodefreshInfo.CODEFRESH) != null) {
      return new CodefreshInfo(environment);
    } else if (System.getenv(TeamcityInfo.TEAMCITY) != null) {
      return new TeamcityInfo(environment);
    } else if (System.getenv(AwsCodePipelineInfo.AWS_CODEPIPELINE) != null
        && System.getenv(AwsCodePipelineInfo.AWS_CODEPIPELINE).startsWith("codepipeline")) {
      return new AwsCodePipelineInfo(environment);
    } else {
      return new UnknownCIInfo(environment, targetFolder, currentPath);
    }
  }
}
