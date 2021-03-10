package datadog.trace.bootstrap.instrumentation.ci.git;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GitObject {

  public static final GitObject NOOP = GitObject.builder().build();

  private final String type;
  private final int size;
  private final byte[] content;
}
