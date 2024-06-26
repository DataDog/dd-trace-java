package datadog.trace.civisibility.source;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BestEffortSourcePathResolver implements SourcePathResolver {

  private final SourcePathResolver[] delegates;

  public BestEffortSourcePathResolver(SourcePathResolver... delegates) {
    this.delegates = delegates;
  }

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) {
    for (SourcePathResolver delegate : delegates) {
      String sourcePath = delegate.getSourcePath(c);
      if (sourcePath != null) {
        return sourcePath;
      }
    }
    return null;
  }

  @Nullable
  @Override
  public String getResourcePath(@Nullable String relativePath) {
    for (SourcePathResolver delegate : delegates) {
      String resourcePath = delegate.getResourcePath(relativePath);
      if (resourcePath != null) {
        return resourcePath;
      }
    }
    return null;
  }
}
