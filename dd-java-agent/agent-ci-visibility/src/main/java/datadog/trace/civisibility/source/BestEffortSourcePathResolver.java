package datadog.trace.civisibility.source;

import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BestEffortSourcePathResolver implements SourcePathResolver {

  private final SourcePathResolver[] delegates;

  public BestEffortSourcePathResolver(SourcePathResolver... delegates) {
    this.delegates = delegates;
  }

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) throws SourceResolutionException {
    for (SourcePathResolver delegate : delegates) {
      String sourcePath = delegate.getSourcePath(c);
      if (sourcePath != null) {
        return sourcePath;
      }
    }
    return null;
  }

  @Nonnull
  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) throws SourceResolutionException {
    for (SourcePathResolver delegate : delegates) {
      Collection<String> sourcePaths = delegate.getSourcePaths(c);
      if (!sourcePaths.isEmpty()) {
        return sourcePaths;
      }
    }
    return Collections.emptyList();
  }

  @Nullable
  @Override
  public String getResourcePath(@Nullable String relativePath) throws SourceResolutionException {
    for (SourcePathResolver delegate : delegates) {
      String resourcePath = delegate.getResourcePath(relativePath);
      if (resourcePath != null) {
        return resourcePath;
      }
    }
    return null;
  }
}
