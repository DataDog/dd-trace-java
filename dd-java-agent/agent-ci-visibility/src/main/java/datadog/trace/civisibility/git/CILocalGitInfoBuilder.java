package datadog.trace.civisibility.git;

import datadog.trace.api.civisibility.telemetry.tag.GitProviderDiscrepant;
import datadog.trace.api.civisibility.telemetry.tag.GitProviderExpected;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoBuilder;
import datadog.trace.civisibility.git.tree.GitClient;
import datadog.trace.util.Strings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CILocalGitInfoBuilder implements GitInfoBuilder {

  private static final Logger LOGGER = LoggerFactory.getLogger(CILocalGitInfoBuilder.class);

  private final GitClient.Factory gitClientFactory;
  private final String gitFolderName;

  public CILocalGitInfoBuilder(GitClient.Factory gitClientFactory, String gitFolderName) {
    this.gitClientFactory = gitClientFactory;
    this.gitFolderName = gitFolderName;
  }

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    if (repositoryPath == null) {
      return GitInfo.NOOP;
    }

    Path gitPath = getGitPath(repositoryPath);
    return new LocalFSGitInfoExtractor().headCommit(gitPath.toFile().getAbsolutePath());
  }

  private Path getGitPath(String repositoryPath) {
    try {
      GitClient gitClient = gitClientFactory.create(repositoryPath);
      String gitFolder = gitClient.getGitFolder();
      if (Strings.isNotBlank(gitFolder)) {
        Path gitFolderPath = Paths.get(gitFolder);
        if (Files.exists(gitFolderPath)) {
          return gitFolderPath;
        }
      }
    } catch (Exception e) {
      LOGGER.debug("Error while getting Git folder in {}", repositoryPath, e);
      LOGGER.warn("Error while getting Git folder");
    }
    return Paths.get(repositoryPath, gitFolderName);
  }

  @Override
  public int order() {
    return 2;
  }

  @Override
  public GitProviderExpected providerAsExpected() {
    return GitProviderExpected.LOCAL_GIT;
  }

  @Override
  public GitProviderDiscrepant providerAsDiscrepant() {
    return GitProviderDiscrepant.LOCAL_GIT;
  }
}
