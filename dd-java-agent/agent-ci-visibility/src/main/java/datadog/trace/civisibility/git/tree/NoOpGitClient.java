package datadog.trace.civisibility.git.tree;

import datadog.trace.civisibility.diff.LineDiff;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NoOpGitClient implements GitClient {

  public static final GitClient INSTANCE = new NoOpGitClient();

  private NoOpGitClient() {}

  @Override
  public boolean isShallow() {
    return false;
  }

  @Override
  public void unshallow(@Nullable String remoteCommitReference) {
    // no op
  }

  @Nullable
  @Override
  public String getGitFolder() {
    return null;
  }

  @Nullable
  @Override
  public String getRepoRoot() {
    return null;
  }

  @Nullable
  @Override
  public String getRemoteUrl(String remoteName) {
    return null;
  }

  @Nullable
  @Override
  public String getUpstreamBranchSha() {
    return null;
  }

  @Nullable
  @Override
  public String getCurrentBranch() {
    return null;
  }

  @NotNull
  @Override
  public List<String> getTags(String commit) {
    return Collections.emptyList();
  }

  @Nullable
  @Override
  public String getSha(String reference) {
    return null;
  }

  @Nullable
  @Override
  public String getFullMessage(String commit) {
    return null;
  }

  @Nullable
  @Override
  public String getAuthorName(String commit) {
    return null;
  }

  @Nullable
  @Override
  public String getAuthorEmail(String commit) {
    return null;
  }

  @Nullable
  @Override
  public String getAuthorDate(String commit) {
    return null;
  }

  @Nullable
  @Override
  public String getCommitterName(String commit) {
    return null;
  }

  @Nullable
  @Override
  public String getCommitterEmail(String commit) {
    return null;
  }

  @Nullable
  @Override
  public String getCommitterDate(String commit) {
    return null;
  }

  @NotNull
  @Override
  public List<String> getLatestCommits() {
    return Collections.emptyList();
  }

  @NotNull
  @Override
  public List<String> getObjects(
      Collection<String> commitsToSkip, Collection<String> commitsToInclude) {
    return Collections.emptyList();
  }

  @Override
  public Path createPackFiles(List<String> objectHashes) {
    return null;
  }

  @Nullable
  @Override
  public LineDiff getGitDiff(String baseCommit, String targetCommit) {
    return null;
  }
}
