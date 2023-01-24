package datadog.trace.bootstrap.instrumentation.ci.source;

import javax.annotation.Nullable;

public interface SourcePathResolver {

  /**
   * @return path to the source file corresponding to the provided class, relative to repository
   *     root. {@code null} is returned if the path could not be resolved
   */
  @Nullable
  String getSourcePath(Class<?> c);

  interface Factory {
    SourcePathResolver create(String repoRoot);
  }
}
