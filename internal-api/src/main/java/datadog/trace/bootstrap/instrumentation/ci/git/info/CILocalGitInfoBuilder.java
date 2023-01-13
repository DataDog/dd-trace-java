package datadog.trace.bootstrap.instrumentation.ci.git.info;

import datadog.trace.bootstrap.instrumentation.ci.CIInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;
import datadog.trace.bootstrap.instrumentation.ci.git.LocalFSGitInfoExtractor;
import java.nio.file.Paths;

public class CILocalGitInfoBuilder {
  public GitInfo build(CIInfo ciInfo, String gitFolderName) {
    if (ciInfo.getCiWorkspace() == null) {
      return GitInfo.NOOP;
    }
    return new LocalFSGitInfoExtractor()
        .headCommit(Paths.get(ciInfo.getCiWorkspace(), gitFolderName).toFile().getAbsolutePath());
  }
}
