package datadog.trace.civisibility.git.tree;

import datadog.trace.api.Config;
import datadog.trace.civisibility.utils.FileUtils;
import datadog.trace.util.AgentThreadFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitDataUploaderImpl implements GitDataUploader {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitDataUploaderImpl.class);

  private final Config config;
  private final GitDataApi gitDataApi;
  private final GitClient gitClient;
  private final String remoteName;
  private final Thread uploadFinishedShutdownHook;
  private volatile CompletableFuture<Void> callback;

  public GitDataUploaderImpl(
      Config config, GitDataApi gitDataApi, GitClient gitClient, String remoteName) {
    this.config = config;
    this.gitDataApi = gitDataApi;
    this.gitClient = gitClient;
    this.remoteName = remoteName;

    // maven has a way of calling System.exit() when the build is done.
    // this is a hack to make it wait until git data upload has finished
    uploadFinishedShutdownHook =
        AgentThreadFactory.newAgentThread(
            AgentThreadFactory.AgentThread.CI_GIT_DATA_SHUTDOWN_HOOK,
            this::waitForUploadToFinish,
            false);
  }

  /**
   * Starts Git data upload, if not started yet. Returns a {@code Future} that can be used to wait
   * for and check the status of the upload.
   */
  @Override
  public Future<Void> startOrObserveGitDataUpload() {
    if (callback == null) {
      synchronized (this) {
        if (callback == null) {

          callback = new CompletableFuture<>();
          Runtime.getRuntime().addShutdownHook(uploadFinishedShutdownHook);

          Thread gitTreeDataUploadThread =
              AgentThreadFactory.newAgentThread(
                  AgentThreadFactory.AgentThread.CI_GIT_DATA_UPLOADER, this::uploadGitData, false);
          // we want to wait for git data upload to finish
          // even if tests finish faster
          gitTreeDataUploadThread.setDaemon(false);
          gitTreeDataUploadThread.start();
        }
      }
    }
    return callback;
  }

  private void uploadGitData() {
    try {
      String remoteUrl = gitClient.getRemoteUrl(remoteName);
      List<String> latestCommits = gitClient.getLatestCommits();
      if (latestCommits.isEmpty()) {
        LOGGER.debug("No commits in the last month");
        callback.complete(null);
        return;
      }

      List<String> commitsToSkip = gitDataApi.searchCommits(remoteUrl, latestCommits);
      List<String> objectHashes = gitClient.getObjects(commitsToSkip);
      if (objectHashes.isEmpty()) {
        LOGGER.debug("No git objects to upload");
        callback.complete(null);
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

      LOGGER.info("Git data upload finished, {}", gitClient);
      callback.complete(null);

    } catch (Exception e) {
      LOGGER.error("Failed to upload git tree data for remote {}", remoteName, e);
      callback.completeExceptionally(e);
    } finally {
      Runtime.getRuntime().removeShutdownHook(uploadFinishedShutdownHook);
    }
  }

  private void waitForUploadToFinish() {
    try {
      long uploadTimeoutMillis = config.getCiVisibilityGitUploadTimeoutMillis();
      callback.get(uploadTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      LOGGER.warn("Timeout while waiting for Git data upload to finish", e);
    } catch (Exception e) {
      LOGGER.error("Error while waiting for Git data upload to finish", e);
    }
  }
}
