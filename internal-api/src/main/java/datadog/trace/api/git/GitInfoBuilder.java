package datadog.trace.api.git;

import javax.annotation.Nullable;

public interface GitInfoBuilder {
  GitInfo build(@Nullable String repositoryPath);

  int order();
}
