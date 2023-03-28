package datadog.trace.civisibility.git.info;

import datadog.trace.api.civisibility.git.GitInfo;
import datadog.trace.civisibility.git.LocalFSGitInfoExtractor;
import java.nio.file.Paths;

public class CILocalGitInfoBuilder {
  public GitInfo build(String ciWorkspace, String gitFolderName) {
    if (ciWorkspace == null) {
      return GitInfo.NOOP;
    }
    return new LocalFSGitInfoExtractor()
        .headCommit(Paths.get(ciWorkspace, gitFolderName).toFile().getAbsolutePath());
  }
}
