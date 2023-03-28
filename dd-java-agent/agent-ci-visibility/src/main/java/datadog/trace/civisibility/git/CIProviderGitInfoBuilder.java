package datadog.trace.civisibility.git;

import datadog.trace.api.civisibility.CIProviderInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import datadog.trace.civisibility.CIProviderInfoFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

public class CIProviderGitInfoBuilder implements GitInfoBuilder {
  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    Path currentPath = repositoryPath != null ? Paths.get(repositoryPath) : null;
    CIProviderInfo ciProviderInfo = CIProviderInfoFactory.createCIProviderInfo(currentPath);
    return ciProviderInfo.buildCIGitInfo();
  }
}
