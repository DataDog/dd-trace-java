package datadog.trace.civisibility.source;

import datadog.trace.api.civisibility.source.SourcePathResolver;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BestEfforSourcePathResolver implements SourcePathResolver {

  private final SourcePathResolver[] delegates;

  public BestEfforSourcePathResolver(SourcePathResolver... delegates) {
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
}
