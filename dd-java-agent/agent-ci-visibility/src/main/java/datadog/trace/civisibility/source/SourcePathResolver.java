package datadog.trace.civisibility.source;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface SourcePathResolver {

  /**
   * @return path to the source file corresponding to the provided class, relative to repository
   *     root. {@code null} is returned if the path could not be resolved
   */
  @Nullable
  String getSourcePath(@Nonnull Class<?> c);
}
