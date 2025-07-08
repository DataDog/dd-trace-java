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
    if (environment.get(JenkinsInfo.JENKINS) != null) {
      return new JenkinsInfo(environment);
    } else if (environment.get(GitLabInfo.GITLAB) != null) {
      return new GitLabInfo(environment);
    } else if (environment.get(TravisInfo.TRAVIS) != null) {
      return new TravisInfo(environment);
    } else if (environment.get(CircleCIInfo.CIRCLECI) != null) {
      return new CircleCIInfo(environment);
    } else if (environment.get(AppVeyorInfo.APPVEYOR) != null) {
      return new AppVeyorInfo(environment);
    } else if (environment.get(AzurePipelinesInfo.AZURE) != null) {
      return new AzurePipelinesInfo(environment);
    } else if (environment.get(BitBucketInfo.BITBUCKET) != null) {
      return new BitBucketInfo(environment);
    } else if (environment.get(GithubActionsInfo.GHACTIONS) != null) {
      return new GithubActionsInfo(environment);
    } else if (environment.get(BuildkiteInfo.BUILDKITE) != null) {
      return new BuildkiteInfo(environment);
    } else if (environment.get(BitriseInfo.BITRISE) != null) {
      return new BitriseInfo(environment);
    } else if (environment.get(BuddyInfo.BUDDY) != null) {
      return new BuddyInfo(environment);
    } else if (environment.get(CodefreshInfo.CODEFRESH) != null) {
      return new CodefreshInfo(environment);
    } else if (environment.get(TeamcityInfo.TEAMCITY) != null) {
      return new TeamcityInfo(environment);
    } else if (environment.get(AwsCodePipelineInfo.AWS_CODEPIPELINE) != null
        && environment.get(AwsCodePipelineInfo.AWS_CODEPIPELINE).startsWith("codepipeline")) {
      return new AwsCodePipelineInfo(environment);
    } else if (environment.get(DroneInfo.DRONE) != null) {
      return new DroneInfo(environment);
    } else {
      return new UnknownCIInfo(environment, targetFolder, currentPath);
    }
  }
}
