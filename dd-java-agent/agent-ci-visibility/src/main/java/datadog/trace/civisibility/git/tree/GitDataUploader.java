package datadog.trace.civisibility.git.tree;

import datadog.trace.civisibility.utils.FileUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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
      if (latestCommits.isEmpty()) {
        LOGGER.debug("No commits in the last month");
        return;
      }

      List<String> commitsToSkip = gitDataApi.searchCommits(remoteUrl, latestCommits);
      List<String> objectHashes = gitClient.getObjects(commitsToSkip);
      if (objectHashes.isEmpty()) {
        LOGGER.debug("No git objects to upload");
        return;
      }

      String currentCommit = latestCommits.get(0);

      Path packFilesDirectory = gitClient.createPackFiles(objectHashes);
      try (Stream<Path> packFiles = Files.list(packFilesDirectory)) {
        packFiles
            .filter(pf -> pf.getFileName().toString().endsWith(".pack")) // skipping ".idx" files
            .forEach(
                pf -> {
                  try {
                    gitDataApi.uploadPackFile(remoteUrl, currentCommit, pf);
                  } catch (Exception e) {
                    throw new RuntimeException("Could not upload pack file " + pf, e);
                  }
                });
      } finally {
        FileUtils.delete(packFilesDirectory);
      }

    } catch (Exception e) {
      LOGGER.error("Failed to upload git tree data for remote {}", remoteName, e);
    }
  }
}
