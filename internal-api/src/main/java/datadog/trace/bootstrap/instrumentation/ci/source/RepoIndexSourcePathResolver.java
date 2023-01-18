package datadog.trace.bootstrap.instrumentation.ci.source;

import javax.annotation.Nullable;

public class RepoIndexSourcePathResolver implements SourcePathResolver {

  private final String ciWorkspace;

  public RepoIndexSourcePathResolver(String ciWorkspace) {
    this.ciWorkspace = ciWorkspace;
  }

  @Nullable
  @Override
  public String getSourcePath(Class<?> c) {
    // FIXME init index lazily
    return null;
  }
}
