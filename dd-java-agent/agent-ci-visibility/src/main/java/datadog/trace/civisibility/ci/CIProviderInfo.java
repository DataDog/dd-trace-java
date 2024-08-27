package datadog.trace.civisibility.ci;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.GitInfo;

public interface CIProviderInfo {

  GitInfo buildCIGitInfo();

  CIInfo buildCIInfo();

  Provider getProvider();
}
