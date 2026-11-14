package datadog.trace.civisibility.git;

import static java.util.Collections.emptyList;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitClientGitInfoBuilder implements GitInfoBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitClientGitInfoBuilder.class);

  private final Config config;
  private final GitClient.Factory gitClientFactory;

  public GitClientGitInfoBuilder(Config config, GitClient.Factory gitClientFactory) {
    this.config = config;
    this.gitClientFactory = gitClientFactory;
  }

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    if (repositoryPath == null) {
      return GitInfo.NOOP;
    }

    GitClient gitClient = gitClientFactory.create(repositoryPath);
    try {
      String remoteName = config.getCiVisibilityGitRemoteName();
      String remoteUrl = null;
      try {
        remoteUrl = gitClient.getRemoteUrl(remoteName);
      } catch (ShellCommandExecutor.ShellCommandFailedException e) {
        LOGGER.debug("Could not read Git remote URL from {}", repositoryPath, e);
      }
      String branch = gitClient.getCurrentBranch();
      List<String> tags =
          branch == null || branch.isEmpty() ? gitClient.getTags(GitClient.HEAD) : emptyList();
      String tag = tags.size() == 1 ? tags.get(0) : null;

      CommitInfo commitInfo = gitClient.getCommitInfo(GitClient.HEAD, false);
      return new GitInfo(remoteUrl, branch, tag, commitInfo);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return GitInfo.NOOP;
    } catch (Exception e) {
      LOGGER.debug("Error while getting Git data from {}", repositoryPath, e);
      LOGGER.warn("Error while getting Git data by executing shell commands");
      return GitInfo.NOOP;
    }
  }

  @Override
  public int order() {
    return 1;
  }

  @Override
  public GitProviderExpected providerAsExpected() {
    return GitProviderExpected.GIT_CLIENT;
  }

  @Override
  public GitProviderDiscrepant providerAsDiscrepant() {
    return GitProviderDiscrepant.GIT_CLIENT;
  }
}
