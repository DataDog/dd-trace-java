package datadog.trace.civisibility.git;

import datadog.trace.api.Config;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

public class CIProviderGitInfoBuilder implements GitInfoBuilder {
  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    Path currentPath = repositoryPath != null ? Paths.get(repositoryPath) : null;
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(Config.get());
    CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(currentPath);
    return ciProviderInfo.buildCIGitInfo();
  }

  @Override
  public int order() {
    return 1;
  }
}
