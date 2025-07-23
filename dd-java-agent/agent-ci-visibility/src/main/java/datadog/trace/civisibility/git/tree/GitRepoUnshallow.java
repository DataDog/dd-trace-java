package datadog.trace.civisibility.git.tree;

import datadog.trace.api.Config;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitRepoUnshallow {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitRepoUnshallow.class);

  private final Config config;
  private final GitClient gitClient;

  public GitRepoUnshallow(Config config, GitClient gitClient) {
    this.config = config;
    this.gitClient = gitClient;
  }

  /**
   * Unshallows git repo up to a specific boundary commit, if provided, or up to the time limit
   * configured in the git client if not. Won't perform an unshallow action when a boundary is
   * provided and the object is already present, or if the repository is already unshallowed.
   *
   * @param boundaryCommitSha used as boundary for the unshallowing if provided.
   * @return false if unshallowing is not enabled or unnecessary, true otherwise
   */
  public synchronized boolean unshallow(@Nullable String boundaryCommitSha)
      throws IOException, InterruptedException, TimeoutException {
    if (!config.isCiVisibilityGitUnshallowEnabled()
        || (boundaryCommitSha != null && gitClient.isCommitPresent(boundaryCommitSha))
        || !gitClient.isShallow()) {
      return false;
    }

    long unshallowStart = System.currentTimeMillis();
    if (boundaryCommitSha != null) {
      try {
        gitClient.unshallow(boundaryCommitSha, true);
      } catch (ShellCommandExecutor.ShellCommandFailedException e) {
        LOGGER.debug("Could not unshallow to specific boundary {}", boundaryCommitSha, e);
      }
    } else {
      try {
        gitClient.unshallow(GitClient.HEAD, false);
      } catch (ShellCommandExecutor.ShellCommandFailedException e) {
        LOGGER.debug(
            "Could not unshallow using HEAD - assuming HEAD points to a local commit that does not exist in the remote repo",
            e);
      }

      try {
        String upstreamBranch = gitClient.getUpstreamBranchSha();
        gitClient.unshallow(upstreamBranch, false);
      } catch (ShellCommandExecutor.ShellCommandFailedException e) {
        LOGGER.debug(
            "Could not unshallow using upstream branch - assuming currently checked out local branch does not track any remote branch",
            e);
        gitClient.unshallow(null, false);
      }
    }
    LOGGER.debug("Repository unshallowing took {} ms", System.currentTimeMillis() - unshallowStart);
    return true;
  }
}
