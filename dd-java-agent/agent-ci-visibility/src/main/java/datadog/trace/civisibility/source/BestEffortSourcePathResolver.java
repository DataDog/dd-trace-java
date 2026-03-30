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

  @Override
  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    for (SourcePathResolver delegate : delegates) {
      Collection<String> sourcePaths = delegate.getSourcePaths(c);
      if (!sourcePaths.isEmpty()) {
        return sourcePaths;
      }
    }
    return Collections.emptyList();
  }

  @Override
  public Collection<String> getResourcePaths(@Nullable String relativePath) {
    for (SourcePathResolver delegate : delegates) {
      Collection<String> resourcePath = delegate.getResourcePaths(relativePath);
      if (!resourcePath.isEmpty()) {
        return resourcePath;
      }
    }
    return null;
  }
}
