package datadog.trace.bootstrap.instrumentation.ci;

import static datadog.trace.bootstrap.instrumentation.ci.utils.CIUtils.findPathBackwards;

import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import java.nio.file.Path;
import java.nio.file.Paths;

class UnknownCIInfo extends CIProviderInfo {

  public static final String UNKNOWN_PROVIDER_NAME = "unknown";

  UnknownCIInfo() {}

  @Override
  protected GitInfo buildCIGitInfo() {
    return GitInfo.NOOP;
  }

  @Override
  protected CIInfo buildCIInfo() {
    final Path workspace = findPathBackwards(getCurrentPath(), getTargetFolder(), true);
    if (workspace == null) {
      return CIInfo.NOOP;
    }

    return CIInfo.builder().ciWorkspace(workspace.toAbsolutePath().toString()).build();
  }

  protected String getTargetFolder() {
    return ".git";
  }

  protected Path getCurrentPath() {
    return Paths.get("").toAbsolutePath();
  }

  @Override
  public boolean isCI() {
    return false;
  }
}
