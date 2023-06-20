package datadog.trace.civisibility.git.tree;

import java.util.concurrent.Future;

public interface GitDataUploader {
  Future<Void> startOrObserveGitDataUpload();
}
