package datadog.trace.civisibility;

import datadog.trace.civisibility.git.GitInfo;

public interface CIProviderInfo {

  GitInfo buildCIGitInfo();

  CIInfo buildCIInfo();

  boolean isCI();
}
