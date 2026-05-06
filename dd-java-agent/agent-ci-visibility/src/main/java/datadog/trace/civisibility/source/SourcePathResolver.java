package datadog.trace.civisibility.source;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface SourcePathResolver {

  /**
   * @return paths to the source files corresponding to the provided class, relative to repository
   *     root. Returns all candidate paths when multiple matches exist (e.g. duplicate trie keys in
   *     repo index approach). Empty collection is returned if no paths could be resolved.
   */
  Collection<String> getSourcePaths(@Nonnull Class<?> c);

  /**
   * @param relativePath Path to a resource in current run's repository, relative to a resource root
   * @return Candidate paths relative to repository root
   */
  Collection<String> getResourcePaths(@Nullable String relativePath);
}
