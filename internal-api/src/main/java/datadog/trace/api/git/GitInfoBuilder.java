package datadog.trace.api.git;

import datadog.trace.api.civisibility.telemetry.tag.GitProvider;
import javax.annotation.Nullable;

public interface GitInfoBuilder {
  GitInfo build(@Nullable String repositoryPath);

  int order();

  GitProvider getProvider(GitProvider.Type type);
}
