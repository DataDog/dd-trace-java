package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.isFullLengthCommitSha;
import static datadog.trace.util.Strings.isBlank;
import static datadog.trace.util.Strings.isNotBlank;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

/** Relates the tested checkout to the CI provider's workspace and revisions. */
public final class GitCheckoutInfo {
  private final boolean ciWorkspace;
  private final boolean custom;
  private final String[] ciRevisions;

  public GitCheckoutInfo(
      @Nullable String checkoutRoot, @Nullable String ciWorkspace, String... ciRevisions) {
    boolean comparable = isNotBlank(checkoutRoot) && isNotBlank(ciWorkspace);
    this.ciWorkspace = comparable && samePath(checkoutRoot, ciWorkspace);
    this.custom = comparable && !this.ciWorkspace;
    this.ciRevisions = ciRevisions;
  }

  /** Whether the checkout root is the CI workspace. */
  public boolean isCiWorkspace() {
    return ciWorkspace;
  }

  /** Whether the checkout root is known and differs from the CI workspace. */
  public boolean isCustom() {
    return custom;
  }

  public boolean hasCiRevision() {
    for (String revision : ciRevisions) {
      if (isNotBlank(revision)) {
        return true;
      }
    }
    return false;
  }

  public boolean matchesCiRevision(@Nullable String sha) {
    if (!isFullLengthCommitSha(sha)) {
      return false;
    }
    for (String revision : ciRevisions) {
      if (isFullLengthCommitSha(revision) && sha.equalsIgnoreCase(revision)) {
        return true;
      }
    }
    return false;
  }

  static boolean samePath(String left, String right) {
    if (isBlank(left) || isBlank(right)) {
      return false;
    }
    try {
      Path a = Paths.get(left).toAbsolutePath().normalize();
      Path b = Paths.get(right).toAbsolutePath().normalize();
      return a.equals(b) || Files.isSameFile(a, b);
    } catch (Exception ignored) {
      return false;
    }
  }
}
