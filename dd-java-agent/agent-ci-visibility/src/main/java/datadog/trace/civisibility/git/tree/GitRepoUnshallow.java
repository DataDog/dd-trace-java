package datadog.trace.civisibility.git.tree;

import datadog.trace.api.Config;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
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

  public synchronized boolean unshallow()
      throws IOException, InterruptedException, TimeoutException {
    if (!config.isCiVisibilityGitUnshallowEnabled() || !gitClient.isShallow()) {
      return false;
    }

    long unshallowStart = System.currentTimeMillis();
    try {
      gitClient.unshallow(GitClient.HEAD);
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug(
          "Could not unshallow using HEAD - assuming HEAD points to a local commit that does not exist in the remote repo",
          e);
    }

    try {
      String upstreamBranch = gitClient.getUpstreamBranchSha();
      gitClient.unshallow(upstreamBranch);
    } catch (ShellCommandExecutor.ShellCommandFailedException e) {
      LOGGER.debug(
          "Could not unshallow using upstream branch - assuming currently checked out local branch does not track any remote branch",
          e);
      gitClient.unshallow(null);
    }
    LOGGER.debug("Repository unshallowing took {} ms", System.currentTimeMillis() - unshallowStart);
    return true;
  }
}
