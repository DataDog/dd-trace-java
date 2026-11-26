package datadog.trace.civisibility.git;

import static datadog.trace.util.Strings.coalesce;
import static datadog.trace.util.Strings.isBlank;
import static datadog.trace.util.Strings.isNotBlank;
import static java.util.Collections.emptyList;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import datadog.trace.civisibility.ci.CIProviderInfo;
import datadog.trace.civisibility.ci.CIProviderInfoFactory;
import datadog.trace.civisibility.ci.GitCheckoutInfo;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Paths;
import java.util.List;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitClientGitInfoBuilder implements GitInfoBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitClientGitInfoBuilder.class);

  private final Config config;
  private final GitClient.Factory gitClientFactory;

  private final CIProviderInfoFactory ciProviderInfoFactory;

  public GitClientGitInfoBuilder(Config config, GitClient.Factory gitClientFactory) {
    this(config, gitClientFactory, null);
  }

  public GitClientGitInfoBuilder(
      Config config,
      GitClient.Factory gitClientFactory,
      CIProviderInfoFactory ciProviderInfoFactory) {
    this.config = config;
    this.gitClientFactory = gitClientFactory;
    this.ciProviderInfoFactory = ciProviderInfoFactory;
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
      if (isBlank(branch) && ciProviderInfoFactory != null) {
        try {
          CIProviderInfo provider =
              ciProviderInfoFactory.createCIProviderInfo(Paths.get(repositoryPath));
          GitInfo ci = provider.buildCIGitInfo();
          GitCheckoutInfo checkout =
              new GitCheckoutInfo(
                  gitClient.getRepoRoot(),
                  provider.buildCIInfo().getCiWorkspace(),
                  ci.getCommit().getSha(),
                  provider.buildPullRequestInfo().getHeadCommit().getSha(),
                  provider.getMergeCommitSha());
          // A detached CI checkout takes its branch, tag and canonical URL from the CI provider
          if (checkout.isCiWorkspace() && checkout.matchesCiRevision(commitInfo.getSha())) {
            remoteUrl = coalesce(ci.getRepositoryURL(), remoteUrl);
            branch = coalesce(ci.getBranch(), branch);
            if (isNotBlank(ci.getBranch()) || isNotBlank(ci.getTag())) {
              tag = ci.getTag();
            }
          }
        } catch (InterruptedException e) {
          throw e;
        } catch (Exception e) {
          LOGGER.debug("Could not match checkout with CI identity", e);
        }
      }
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
