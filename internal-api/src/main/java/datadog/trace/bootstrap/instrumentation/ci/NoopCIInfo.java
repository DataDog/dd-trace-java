package datadog.trace.bootstrap.instrumentation.ci;

import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;

class NoopCIInfo extends CIProviderInfo {

  public static final String NOOP_PROVIDER_NAME = "noop";

  NoopCIInfo() {}

  @Override
  protected GitInfo buildCIGitInfo() {
    return GitInfo.NOOP;
  }

  @Override
  protected CIInfo buildCIInfo() {
    return CIInfo.NOOP;
  }

  @Override
  public boolean isCI() {
    return false;
  }
}
