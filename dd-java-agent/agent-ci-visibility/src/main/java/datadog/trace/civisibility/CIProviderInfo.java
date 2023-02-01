package datadog.trace.bootstrap.instrumentation.ci;

import datadog.trace.bootstrap.instrumentation.ci.git.GitInfo;

public interface CIProviderInfo {

  GitInfo buildCIGitInfo();

  CIInfo buildCIInfo();

  boolean isCI();
}
