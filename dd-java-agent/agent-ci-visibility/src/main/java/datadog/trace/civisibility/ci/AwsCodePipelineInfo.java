package datadog.trace.civisibility.ci;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

class AwsCodePipelineInfo implements CIProviderInfo {

  public static final String AWS_CODEPIPELINE = "CODEBUILD_INITIATOR";
  public static final String AWS_CODEPIPELINE_PROVIDER_NAME = "awscodepipeline";
  public static final String AWS_CODEPIPELINE_EXECUTION_ID = "DD_PIPELINE_EXECUTION_ID";
  public static final String AWS_CODEPIPELINE_ACTION_EXECUTION_ID = "DD_ACTION_EXECUTION_ID";
  public static final String AWS_CODEPIPELINE_ARN = "CODEBUILD_BUILD_ARN";

  private final CiEnvironment environment;

  AwsCodePipelineInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return GitInfo.NOOP;
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder(environment)
        .ciProviderName(AWS_CODEPIPELINE_PROVIDER_NAME)
        .ciPipelineId(environment.get(AWS_CODEPIPELINE_EXECUTION_ID))
        .ciJobId(environment.get(AWS_CODEPIPELINE_ACTION_EXECUTION_ID))
        .ciEnvVars(
            AWS_CODEPIPELINE_EXECUTION_ID,
            AWS_CODEPIPELINE_ACTION_EXECUTION_ID,
            AWS_CODEPIPELINE_ARN)
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return PullRequestInfo.EMPTY;
  }

  @Override
  public Provider getProvider() {
    return Provider.AWS;
  }
}
