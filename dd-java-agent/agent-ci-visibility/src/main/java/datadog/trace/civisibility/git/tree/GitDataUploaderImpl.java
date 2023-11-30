package datadog.trace.civisibility.git.tree;

import datadog.trace.api.Config;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.civisibility.utils.FileUtils;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
  private final GitInfoProvider gitInfoProvider;
  private final String repoRoot;
  private final String remoteName;
  private final Thread uploadFinishedShutdownHook;
  private volatile CompletableFuture<Void> callback;

  public GitDataUploaderImpl(
      Config config,
      GitDataApi gitDataApi,
      GitClient gitClient,
      GitInfoProvider gitInfoProvider,
      String repoRoot,
      String remoteName) {
    this.config = config;
    this.gitDataApi = gitDataApi;
    this.gitClient = gitClient;
    this.gitInfoProvider = gitInfoProvider;
    this.repoRoot = repoRoot;
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

          Thread gitDataUploadThread =
              AgentThreadFactory.newAgentThread(
                  AgentThreadFactory.AgentThread.CI_GIT_DATA_UPLOADER, this::uploadGitData, false);
          gitDataUploadThread.start();
        }
      }
    }
    return callback;
  }

  private void uploadGitData() {
    try {
      LOGGER.info("Starting git data upload, {}", gitClient);

      if (config.isCiVisibilityGitUnshallowEnabled()
          && !config.isCiVisibilityGitUnshallowDefer()
          && gitClient.isShallow()) {
        unshallowRepository();
      }

      GitInfo gitInfo = gitInfoProvider.getGitInfo(repoRoot);
      String remoteUrl = gitInfo.getRepositoryURL();
      List<String> latestCommits = gitClient.getLatestCommits();
      if (latestCommits.isEmpty()) {
        LOGGER.info("No commits in the last month, skipping git data upload");
        callback.complete(null);
        return;
      }

      Collection<String> commitsToSkip = gitDataApi.searchCommits(remoteUrl, latestCommits);
      if (commitsToSkip.size() == latestCommits.size()) {
        LOGGER.info(
            "Backend already knows of the {} local commits, skipping git data upload",
            latestCommits.size());
        callback.complete(null);
        return;
      }

      if (config.isCiVisibilityGitUnshallowEnabled()
          && config.isCiVisibilityGitUnshallowDefer()
          && gitClient.isShallow()) {
        unshallowRepository();
        latestCommits = gitClient.getLatestCommits();
        commitsToSkip = gitDataApi.searchCommits(remoteUrl, latestCommits);
      }

      Collection<String> commitsToInclude =
          new ArrayList<>(latestCommits.size() - commitsToSkip.size());
      for (String commit : latestCommits) {
        if (!commitsToSkip.contains(commit)) {
          commitsToInclude.add(commit);
        }
      }

      List<String> objectHashes = gitClient.getObjects(commitsToSkip, commitsToInclude);
      if (objectHashes.isEmpty()) {
        LOGGER.info("No git objects to upload");
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

      LOGGER.info("Git data upload finished");
      callback.complete(null);

    } catch (Exception e) {
      LOGGER.error("Failed to upload git tree data for remote {}", remoteName, e);
      callback.completeExceptionally(e);
    } finally {
      Runtime.getRuntime().removeShutdownHook(uploadFinishedShutdownHook);
    }
  }

  private void unshallowRepository() throws IOException, TimeoutException, InterruptedException {
    long unshallowStart = System.currentTimeMillis();
    try {
      gitClient.unshallow(GitClient.HEAD);
      return;
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
    LOGGER.info("Repository unshallowing took {} ms", System.currentTimeMillis() - unshallowStart);
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
