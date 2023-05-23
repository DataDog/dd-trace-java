package datadog.trace.civisibility.git.tree;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitDataUploader {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitDataUploader.class);

  private final GitDataApi gitDataApi;
  private final GitClient gitClient;
  private final String remoteName;

  public GitDataUploader(GitDataApi gitDataApi, GitClient gitClient, String remoteName) {
    this.gitDataApi = gitDataApi;
    this.gitClient = gitClient;
    this.remoteName = remoteName;
  }

  public void uploadGitData() {
    try {
      String remoteUrl = gitClient.getRemoteUrl(remoteName);
      List<String> latestCommits = gitClient.getLatestCommits();
      List<String> commitsToSkip = gitDataApi.searchCommits(remoteUrl, latestCommits);

    } catch (Exception e) {
      LOGGER.error("Failed to upload git tree data for remote {}", remoteName, e);
    }
  }
}
