package datadog.trace.civisibility.git;

import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import java.nio.file.Paths;
import javax.annotation.Nullable;

public class CILocalGitInfoBuilder implements GitInfoBuilder {

  private final String gitFolderName;

  public CILocalGitInfoBuilder(String gitFolderName) {
    this.gitFolderName = gitFolderName;
  }

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    if (repositoryPath == null) {
      return GitInfo.NOOP;
    }
    return new LocalFSGitInfoExtractor()
        .headCommit(Paths.get(repositoryPath, gitFolderName).toFile().getAbsolutePath());
  }

  @Override
  public int order() {
    return 2;
  }
}
