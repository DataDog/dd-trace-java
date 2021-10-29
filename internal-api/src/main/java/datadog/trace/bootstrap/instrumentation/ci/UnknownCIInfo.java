package datadog.trace.bootstrap.instrumentation.ci;

import static datadog.trace.bootstrap.instrumentation.ci.utils.CIUtils.findParentPathBackwards;

import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * This class is the strategy to use when the CI provider used to execute the tests cannot be
 * recognized. See selectCI() method in {@code CIProviderInfo} class.
 *
 * <p>In this case, the tests may be being executed either a local development or an unknown CI
 * provider, so we cannot collect the usual data like pipeline, stage, job, etc.
 *
 * <p>However, we would like to provide git information if the user is using git, so we infer the
 * workspace path leveraging the `.git` folder, which is usually kept in the root path of the
 * repository.
 *
 * <p>The workspace path will be used in the CIProviderInfo constructor to access the `.git` folder
 * and calculate the git information properly.
 */
class UnknownCIInfo extends CIProviderInfo {

  public static final String UNKNOWN_PROVIDER_NAME = "unknown";

  UnknownCIInfo() {}

  @Override
  protected GitInfo buildCIGitInfo() {
    return GitInfo.NOOP;
  }

  @Override
  protected CIInfo buildCIInfo() {
    final Path workspace = findParentPathBackwards(getCurrentPath(), getTargetFolder(), true);
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
