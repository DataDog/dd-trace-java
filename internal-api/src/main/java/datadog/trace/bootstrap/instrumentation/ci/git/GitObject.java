package datadog.trace.bootstrap.instrumentation.ci.git;

import java.util.Arrays;
import java.util.Objects;

public class GitObject {

  public static final byte UNKNOWN_TYPE = 0;
  public static final byte COMMIT_TYPE = 1;
  public static final byte TAG_TYPE = 3;

  public static final GitObject NOOP = new GitObject();

  private final byte type;
  private final int size;
  private final byte[] content;

  public GitObject() {
    this((byte) 0, 0, null);
  }

  public GitObject(final byte type, final int size, final byte[] content) {
    this.type = type;
    this.size = size;
    this.content = content;
  }

  public byte getType() {
    return type;
  }

  public int getSize() {
    return size;
  }

  public byte[] getContent() {
    return content;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final GitObject gitObject = (GitObject) o;
    return size == gitObject.size
        && Objects.equals(type, gitObject.type)
        && Arrays.equals(content, gitObject.content);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(type, size);
    result = 31 * result + Arrays.hashCode(content);
    return result;
  }
}
