package datadog.trace.api.git;

import java.util.Objects;

public class GitInfo {

  public static final GitInfo NOOP = new GitInfo(null, null, null, CommitInfo.NOOP);

  private final String repositoryURL;
  private final String branch;
  private final String tag;
  private final CommitInfo commit;

  public GitInfo(String repositoryURL, String branch, String tag, CommitInfo commit) {
    this.repositoryURL = repositoryURL;
    this.branch = branch;
    this.tag = tag;
    this.commit = commit;
  }

  public String getRepositoryURL() {
    return repositoryURL;
  }

  public String getBranch() {
    return branch;
  }

  public String getTag() {
    return tag;
  }

  public CommitInfo getCommit() {
    return commit;
  }

  public boolean isEmpty() {
    return (repositoryURL == null || repositoryURL.isEmpty())
        && (branch == null || branch.isEmpty())
        && (tag == null || tag.isEmpty())
        && (commit == null || commit.isEmpty());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    GitInfo gitInfo = (GitInfo) o;
    return Objects.equals(repositoryURL, gitInfo.repositoryURL)
        && Objects.equals(branch, gitInfo.branch)
        && Objects.equals(tag, gitInfo.tag)
        && Objects.equals(commit, gitInfo.commit);
  }

  @Override
  public int hashCode() {
    int hash = 1;
    hash = 31 * hash + (repositoryURL == null ? 0 : repositoryURL.hashCode());
    hash = 31 * hash + (branch == null ? 0 : branch.hashCode());
    hash = 31 * hash + (tag == null ? 0 : tag.hashCode());
    hash = 31 * hash + (commit == null ? 0 : commit.hashCode());
    return hash;
  }
}
