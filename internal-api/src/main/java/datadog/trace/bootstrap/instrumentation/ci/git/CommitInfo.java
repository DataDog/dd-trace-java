package datadog.trace.bootstrap.instrumentation.ci.git;

import java.util.Objects;

public class CommitInfo {

  public static final CommitInfo NOOP =
      new CommitInfo(null, PersonInfo.NOOP, PersonInfo.NOOP, null);

  private final String sha;
  private final PersonInfo author;
  private final PersonInfo committer;
  private final String fullMessage;

  public CommitInfo(final String sha) {
    this(sha, PersonInfo.NOOP, PersonInfo.NOOP, null);
  }

  public CommitInfo(
      final String sha,
      final PersonInfo author,
      final PersonInfo committer,
      final String fullMessage) {
    this.sha = sha;
    this.author = author;
    this.committer = committer;
    this.fullMessage = fullMessage;
  }

  public String getSha() {
    return sha;
  }

  public PersonInfo getAuthor() {
    return author;
  }

  public PersonInfo getCommitter() {
    return committer;
  }

  public String getFullMessage() {
    return fullMessage;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final CommitInfo that = (CommitInfo) o;
    return Objects.equals(sha, that.sha)
        && Objects.equals(author, that.author)
        && Objects.equals(committer, that.committer)
        && Objects.equals(fullMessage, that.fullMessage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sha, author, committer, fullMessage);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("CommitInfo{");
    sb.append("sha='").append(sha).append('\'');
    sb.append(", author=").append(author);
    sb.append(", committer=").append(committer);
    sb.append(", fullMessage='").append(fullMessage).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
