package datadog.trace.civisibility.git.tree;

import static datadog.trace.civisibility.git.tree.GitTestRepository.client;
import static datadog.trace.civisibility.git.tree.GitTestRepository.fixture;
import static datadog.trace.civisibility.git.tree.GitTestRepository.git;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeCommit;
import static datadog.trace.civisibility.git.tree.GitTestRepository.writeObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class GitRecoveryCompatibilityTest {
  @TempDir Path directory;

  @TableTest({
    "Scenario               | selectedRecoveryFirst",
    "PR metadata first      | false                ",
    "bounded recovery first | true                 "
  })
  void pullRequestMetadataFetchRetainsAncestorsOutsideTheUploadWindow(boolean selectedRecoveryFirst)
      throws Exception {
    Path origin = directory.resolve("origin");
    ShellCommandExecutor remote = fixture(origin);
    Path checkout = cloneFrom(remote, origin);
    String base = git(remote, "rev-parse", "HEAD");
    String tree = git(remote, "rev-parse", "HEAD^{tree}");
    String oldParent =
        writeObject(
            remote,
            "tree "
                + tree
                + "\nparent "
                + base
                + "\nauthor Test <test@example.com> 1 +0000\ncommitter Test <test@example.com> 1 +0000\n\nold ancestor\n");
    String selected = writeCommit(remote, tree, oldParent, "PR source");
    git(remote, "update-ref", "refs/heads/selected", selected);
    ShellGitClient client = client(checkout, "1 month ago");
    if (selectedRecoveryFirst) {
      client.fetchSelectedCommit(selected);
    }
    assertEquals(selected, client.getCommitInfo(selected, true).getSha());
    assertEquals(base, client.getMergeBase(base, selected));
    assertFalse(client.isShallow());
  }

  private Path cloneFrom(ShellCommandExecutor remote, Path origin) throws Exception {
    Path checkout = directory.resolve("checkout");
    git(remote, "clone", "--no-local", origin.toString(), checkout.toString());
    return checkout;
  }
}
