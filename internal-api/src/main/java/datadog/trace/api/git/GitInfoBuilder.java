package datadog.trace.api.git;

import datadog.trace.api.civisibility.telemetry.tag.ExpectedGitProvider;
import datadog.trace.api.civisibility.telemetry.tag.MismatchGitProvider;
import javax.annotation.Nullable;

public interface GitInfoBuilder {
  GitInfo build(@Nullable String repositoryPath);

  int order();

  ExpectedGitProvider getExpectedProviderType();
  MismatchGitProvider getMismatchProviderType();
}
