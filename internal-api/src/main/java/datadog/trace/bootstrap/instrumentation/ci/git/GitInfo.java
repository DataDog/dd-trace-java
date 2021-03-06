package datadog.trace.bootstrap.instrumentation.ci.git;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@Builder
@EqualsAndHashCode
public class GitInfo {

  public static final GitInfo NOOP = GitInfo.builder().commit(CommitInfo.NOOP).build();

  private final String repositoryURL;
  private final String branch;
  private final String tag;
  private final CommitInfo commit;
}
