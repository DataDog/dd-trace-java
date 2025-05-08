package datadog.trace.civisibility.git;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.git.tree.GitClient;
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
      String remoteUrl = gitClient.getRemoteUrl(remoteName);
      String branch = gitClient.getCurrentBranch();
      List<String> tags = gitClient.getTags(GitClient.HEAD);
      String tag = !tags.isEmpty() ? tags.iterator().next() : null;

      String currentCommitSha = gitClient.getSha(GitClient.HEAD);
      String fullMessage = gitClient.getFullMessage(GitClient.HEAD);

      String authorName = gitClient.getAuthorName(GitClient.HEAD);
      String authorEmail = gitClient.getAuthorEmail(GitClient.HEAD);
      String authorDate = gitClient.getAuthorDate(GitClient.HEAD);
      PersonInfo author = new PersonInfo(authorName, authorEmail, authorDate);

      String committerName = gitClient.getCommitterName(GitClient.HEAD);
      String committerEmail = gitClient.getCommitterEmail(GitClient.HEAD);
      String committerDate = gitClient.getCommitterDate(GitClient.HEAD);
      PersonInfo committer = new PersonInfo(committerName, committerEmail, committerDate);

      CommitInfo commitInfo = new CommitInfo(currentCommitSha, author, committer, fullMessage);
      return new GitInfo(remoteUrl, branch, tag, commitInfo);

    } catch (Exception e) {
      LOGGER.debug("Error while getting Git data from {}", repositoryPath, e);
      LOGGER.warn("Error while getting Git data by executing shell commands");
      return GitInfo.NOOP;
    }
  }

  @Override
  public int order() {
    return 3;
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
