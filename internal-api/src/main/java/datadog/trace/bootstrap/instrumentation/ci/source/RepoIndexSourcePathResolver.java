package datadog.trace.bootstrap.instrumentation.ci.source;

import javax.annotation.Nullable;

public class RepoIndexSourcePathResolver implements SourcePathResolver {

  public RepoIndexSourcePathResolver(String ciWorkspace) {
    // FIXME init index
  }

  @Nullable
  @Override
  public String getSourcePath(Class<?> c) {
    return null;
  }
}
