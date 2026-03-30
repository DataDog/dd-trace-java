package datadog.trace.civisibility.source.index;

import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.SourceResolutionException;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RepoIndexSourcePathResolver implements SourcePathResolver {

  private final RepoIndexProvider indexProvider;

  public RepoIndexSourcePathResolver(RepoIndexProvider indexProvider) {
    this.indexProvider = indexProvider;
  }

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) throws SourceResolutionException {
    return indexProvider.getIndex().getSourcePath(c);
  }

  @Nonnull
  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    return indexProvider.getIndex().getSourcePaths(c);
  }

  @Nullable
  @Override
  public String getResourcePath(@Nullable String relativePath) throws SourceResolutionException {
    return indexProvider.getIndex().getSourcePath(relativePath);
  }
}
