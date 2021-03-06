package datadog.trace.bootstrap.instrumentation.ci.git;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@Builder
@EqualsAndHashCode
public class CommitInfo {

  public static final CommitInfo NOOP =
      CommitInfo.builder().author(PersonInfo.NOOP).committer(PersonInfo.NOOP).build();

  private final String sha;
  private final PersonInfo author;
  private final PersonInfo committer;
  private final String fullMessage;
}
