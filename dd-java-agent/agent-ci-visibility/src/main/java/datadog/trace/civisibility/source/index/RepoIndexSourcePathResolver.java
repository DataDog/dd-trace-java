package datadog.trace.civisibility.source.index;

import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RepoIndexSourcePathResolver implements SourcePathResolver {

  private final RepoIndexProvider indexProvider;

  public RepoIndexSourcePathResolver(RepoIndexProvider indexProvider) {
    this.indexProvider = indexProvider;
  }

  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    return indexProvider.getIndex().getSourcePaths(c);
  }

  @Override
  public Collection<String> getResourcePaths(@Nullable String relativePath) {
    return indexProvider.getIndex().getSourcePaths(relativePath);
  }
}
