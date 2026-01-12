package datadog.trace.civisibility.codeowners;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @see <a
 *     href="https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners">CODEOWNERS
 *     file description</a>
 */
public interface Codeowners {
  @Nullable
  Collection<String> getOwners(@Nonnull String path);

  /**
   * @return {@code true} if {@code CODEOWNERS} file could be located and parsed
   */
  boolean exist();
}
