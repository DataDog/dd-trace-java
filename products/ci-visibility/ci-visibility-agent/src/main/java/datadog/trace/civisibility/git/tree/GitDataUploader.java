package datadog.trace.civisibility.git.tree;

import java.util.concurrent.Future;

public interface GitDataUploader {
  /**
   * Starts asynchronous upload of Git data of current local Git repo to server. If the upload is
   * already in progress, nothing happens.
   *
   * @return a {@code Future} instance that can be used to wait for and monitor the status of the
   *     upload
   */
  Future<Void> startOrObserveGitDataUpload();
}
