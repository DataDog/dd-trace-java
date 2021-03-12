package datadog.trace.bootstrap.instrumentation.ci.git;

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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GitInfo gitInfo = (GitInfo) o;
    return Objects.equals(repositoryURL, gitInfo.repositoryURL)
        && Objects.equals(branch, gitInfo.branch)
        && Objects.equals(tag, gitInfo.tag)
        && Objects.equals(commit, gitInfo.commit);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repositoryURL, branch, tag, commit);
  }
}
