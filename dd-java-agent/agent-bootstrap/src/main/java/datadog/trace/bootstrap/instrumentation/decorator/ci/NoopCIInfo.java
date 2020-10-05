package datadog.trace.bootstrap.instrumentation.decorator.ci;

class NoopCIInfo extends CIProviderInfo {

  @Override
  public boolean isCI() {
    return false;
  }

  @Override
  public String getCiProviderName() {
    return null;
  }

  @Override
  public String getCiPipelineId() {
    return null;
  }

  @Override
  public String getCiPipelineName() {
    return null;
  }

  @Override
  public String getCiPipelineNumber() {
    return null;
  }

  @Override
  public String getCiPipelineUrl() {
    return null;
  }

  @Override
  public String getCiJobUrl() {
    return null;
  }

  @Override
  public String getCiWorkspacePath() {
    return null;
  }

  @Override
  public String getGitRepositoryUrl() {
    return null;
  }

  @Override
  public String getGitCommit() {
    return null;
  }

  @Override
  public String getGitBranch() {
    return null;
  }

  @Override
  public String getGitTag() {
    return null;
  }
}
