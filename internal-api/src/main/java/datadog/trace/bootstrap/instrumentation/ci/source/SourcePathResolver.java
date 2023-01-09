package datadog.trace.bootstrap.instrumentation.ci.source;

import javax.annotation.Nullable;

public interface SourcePathResolver {

  /**
   * @return absolute path to the source file corresponding to the provided class. The resolution is
   *     done on the best effort basis, {@code null} is returned if the path could not be resolved
   */
  @Nullable
  String getSourcePath(Class<?> c);
}
