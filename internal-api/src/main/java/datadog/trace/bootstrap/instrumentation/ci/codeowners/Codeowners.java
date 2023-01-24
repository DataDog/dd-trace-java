package datadog.trace.bootstrap.instrumentation.ci.codeowners;

import java.util.Collection;
import javax.annotation.Nullable;

/**
 * @see <a
 *     href="https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners">CODEOWNERS
 *     file description</a>
 */
public interface Codeowners {
  @Nullable
  Collection<String> getOwners(String path);

  interface Factory {
    Codeowners create(String repoRoot);
  }
}
