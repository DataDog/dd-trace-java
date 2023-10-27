package datadog.trace.civisibility.ci

import java.nio.file.Path

class AwsCodePipelineInfoTest extends CITagsProviderTest {

  @Override
  String getProviderName() {
    return AwsCodePipelineInfo.AWS_CODEPIPELINE_PROVIDER_NAME
  }

  @Override
  Map<String, String> buildRemoteGitInfoEmpty() {
    final Map<String, String> map = new HashMap<>()
    map.put(AwsCodePipelineInfo.AWS_CODEPIPELINE, "codepipeline")
    return map
  }

  @Override
  Map<String, String> buildRemoteGitInfoMismatchLocalGit() {
    final Map<String, String> map = new HashMap<>()
    map.put(AwsCodePipelineInfo.AWS_CODEPIPELINE, "codepipeline")
    return map
  }

  @Override
  Path getWorkspacePath() {
    null
  }
}
