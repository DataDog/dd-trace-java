package datadog.trace.civisibility.source.index;

import javax.annotation.Nullable;

public interface RepoIndexProvider {
  RepoIndex getIndex();

  interface Factory {
    RepoIndexProvider create(@Nullable String repoRoot);
  }
}
