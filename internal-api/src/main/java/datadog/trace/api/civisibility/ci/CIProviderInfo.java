package datadog.trace.api.civisibility.ci;

import datadog.trace.api.git.GitInfo;

public interface CIProviderInfo {

  GitInfo buildCIGitInfo();

  CIInfo buildCIInfo();
}
