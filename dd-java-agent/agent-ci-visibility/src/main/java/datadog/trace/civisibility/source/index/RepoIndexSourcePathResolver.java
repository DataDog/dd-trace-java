package datadog.trace.civisibility.source.index;

import datadog.trace.civisibility.source.SourcePathResolver;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RepoIndexSourcePathResolver implements SourcePathResolver {

  private final RepoIndexProvider indexProvider;

  public RepoIndexSourcePathResolver(RepoIndexProvider indexProvider) {
    this.indexProvider = indexProvider;
  }

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) {
    return indexProvider.getIndex().getSourcePath(c);
  }

  @Nullable
  @Override
  public String getResourcePath(@Nullable String relativePath) {
    return indexProvider.getIndex().getSourcePath(relativePath);
  }
}
