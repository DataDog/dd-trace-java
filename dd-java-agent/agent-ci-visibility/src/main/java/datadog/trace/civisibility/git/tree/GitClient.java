package datadog.trace.civisibility.git.tree;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.civisibility.diff.LineDiff;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Client for fetching data and performing operations on a local Git repository. */
public interface GitClient {

  String HEAD = "HEAD";

  boolean isShallow() throws IOException, TimeoutException, InterruptedException;

  boolean isCommitPresent(String commitReference)
      throws IOException, TimeoutException, InterruptedException;

  void unshallow(@Nullable String remoteCommitReference)
      throws IOException, TimeoutException, InterruptedException;

  void fetchCommit(String remoteCommitReference)
      throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getGitFolder() throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getRepoRoot() throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getRemoteUrl(String remoteName) throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getUpstreamBranchSha() throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getCurrentBranch() throws IOException, TimeoutException, InterruptedException;

  @Nonnull
  List<String> getTags(String commit) throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getSha(String reference) throws IOException, TimeoutException, InterruptedException;

  @Nonnull
  CommitInfo getCommitInfo(String commit)
      throws IOException, TimeoutException, InterruptedException;

  @Nonnull
  List<String> getLatestCommits() throws IOException, TimeoutException, InterruptedException;

  @Nonnull
  List<String> getObjects(Collection<String> commitsToSkip, Collection<String> commitsToInclude)
      throws IOException, TimeoutException, InterruptedException;

  Path createPackFiles(List<String> objectHashes)
      throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getBaseCommitSha(@Nullable String baseBranch, @Nullable String defaultBranch)
      throws IOException, TimeoutException, InterruptedException;

  @Nullable
  String getMergeBase(@Nullable String base, @Nullable String source)
      throws IOException, TimeoutException, InterruptedException;

  @Nullable
  LineDiff getGitDiff(String baseCommit, String targetCommit)
      throws IOException, TimeoutException, InterruptedException;

  interface Factory {
    GitClient create(@Nullable String repoRoot);
  }
}
