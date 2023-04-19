package datadog.trace.api.civisibility;

import datadog.trace.api.git.GitInfo;
import java.nio.file.Path;

public interface CIProviderInfo {

  GitInfo buildCIGitInfo();

  CIInfo buildCIInfo();

  boolean isCI();

  interface Factory {
    CIProviderInfo createCIProviderInfo(Path currentPath);
  }
}
