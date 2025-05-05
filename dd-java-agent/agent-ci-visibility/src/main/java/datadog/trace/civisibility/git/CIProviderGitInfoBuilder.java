package datadog.trace.civisibility.git;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.tag.GitProvider;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

public class CIProviderGitInfoBuilder implements GitInfoBuilder {

  private final Config config;
  private final CiEnvironment environment;

  public CIProviderGitInfoBuilder(Config config, CiEnvironment environment) {
    this.config = config;
    this.environment = environment;
  }

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    Path currentPath = repositoryPath != null ? Paths.get(repositoryPath) : null;
    CIProviderInfoFactory ciProviderInfoFactory = new CIProviderInfoFactory(config, environment);
    CIProviderInfo ciProviderInfo = ciProviderInfoFactory.createCIProviderInfo(currentPath);
    return ciProviderInfo.buildCIGitInfo();
  }

  @Override
  public int order() {
    return 1;
  }

  @Override
  public GitProvider getProvider(GitProvider.Type type) {
    if (type == GitProvider.Type.EXPECTED) {
      return GitProvider.CI_PROVIDER_EXPECTED;
    } else {
      return GitProvider.CI_PROVIDER_DISCREPANT;
    }
  }
}
