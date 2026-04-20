package datadog.trace.api.git;

import datadog.trace.util.Strings;
import java.util.Objects;

public final class CommitInfo {
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

  public boolean isEmpty() {
    return Strings.isBlank(sha)
        && (author == null || author.isEmpty())
        && (committer == null || committer.isEmpty())
        && Strings.isBlank(fullMessage);
  }

  public boolean isComplete() {
    return Strings.isNotBlank(sha)
        && (author != null && author.isComplete())
        && (committer != null && committer.isComplete())
        && Strings.isNotBlank(fullMessage);
  }

  /**
   * Combine infos by completing the empty information fields in {@code first} with {@code second}'s
   *
   * @param first Base commit info
   * @param second Fallback commit info
   * @return Combined commit info
   */
  public static CommitInfo coalesce(final CommitInfo first, final CommitInfo second) {
    return new CommitInfo(
        Strings.coalesce(first.sha, second.sha),
        PersonInfo.coalesce(first.author, second.author),
        PersonInfo.coalesce(first.committer, second.committer),
        Strings.coalesce(first.fullMessage, second.fullMessage));
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
    int hash = 1;
    hash = 31 * hash + (sha == null ? 0 : sha.hashCode());
    hash = 31 * hash + (author == null ? 0 : author.hashCode());
    hash = 31 * hash + (committer == null ? 0 : committer.hashCode());
    hash = 31 * hash + (fullMessage == null ? 0 : fullMessage.hashCode());
    return hash;
  }

  @Override
  public String toString() {
    return "CommitInfo{"
        + "sha='"
        + sha
        + '\''
        + ", author="
        + author
        + ", committer="
        + committer
        + ", fullMessage='"
        + fullMessage
        + '\''
        + '}';
  }
}
