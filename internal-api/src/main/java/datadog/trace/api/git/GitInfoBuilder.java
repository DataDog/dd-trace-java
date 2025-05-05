package datadog.trace.api.git;

import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import javax.annotation.Nullable;

public interface GitInfoBuilder {
  GitInfo build(@Nullable String repositoryPath);

  int order();

  GitProviderExpected providerAsExpected();

  GitProviderDiscrepant providerAsDiscrepant();
}
