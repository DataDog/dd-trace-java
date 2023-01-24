package datadog.trace.bootstrap.instrumentation.ci.source;

import javax.annotation.Nullable;

public class BestEfforSourcePathResolver implements SourcePathResolver {

  private final SourcePathResolver[] delegates;

  public BestEfforSourcePathResolver(SourcePathResolver... delegates) {
    this.delegates = delegates;
  }

  @Nullable
  @Override
  public String getSourcePath(Class<?> c) {
    for (SourcePathResolver delegate : delegates) {
      String sourcePath = delegate.getSourcePath(c);
      if (sourcePath != null) {
        return sourcePath;
      }
    }
    return null;
  }
}
