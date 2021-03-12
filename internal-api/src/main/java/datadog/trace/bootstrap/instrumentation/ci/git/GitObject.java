package datadog.trace.bootstrap.instrumentation.ci.git;

import java.util.Arrays;
import java.util.Objects;

public class GitObject {

  public static final GitObject NOOP = new GitObject();

  private final String type;
  private final int size;
  private final byte[] content;

  public GitObject() {
    this(null, 0, null);
  }

  public GitObject(String type, int size, byte[] content) {
    this.type = type;
    this.size = size;
    this.content = content;
  }

  public String getType() {
    return type;
  }

  public int getSize() {
    return size;
  }

  public byte[] getContent() {
    return content;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GitObject gitObject = (GitObject) o;
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
