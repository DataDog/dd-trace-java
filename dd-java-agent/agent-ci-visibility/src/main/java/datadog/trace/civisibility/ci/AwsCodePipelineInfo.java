package datadog.trace.civisibility.ci;

import datadog.trace.api.git.GitInfo;

class AwsCodePipelineInfo implements CIProviderInfo {

  public static final String AWS_CODEPIPELINE = "CODEBUILD_INITIATOR";
  public static final String AWS_CODEPIPELINE_PROVIDER_NAME = "awscodepipeline";
  public static final String AWS_CODEPIPELINE_EXECUTION_ID = "DD_PIPELINE_EXECUTION_ID";
  public static final String AWS_CODEPIPELINE_ACTION_EXECUTION_ID = "DD_ACTION_EXECUTION_ID";
  public static final String AWS_CODEPIPELINE_ARN = "CODEBUILD_BUILD_ARN";

  @Override
  public GitInfo buildCIGitInfo() {
    return GitInfo.NOOP;
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder()
        .ciProviderName(AWS_CODEPIPELINE_PROVIDER_NAME)
        .ciPipelineId(System.getenv(AWS_CODEPIPELINE_EXECUTION_ID))
        .ciEnvVars(
            AWS_CODEPIPELINE_EXECUTION_ID,
            AWS_CODEPIPELINE_ACTION_EXECUTION_ID,
            AWS_CODEPIPELINE_ARN)
        .build();
  }
}
