package datadog.json;

import java.util.Arrays;

/** Represents a mutable path in a hierarchical structure such as JSON. */
public class PathCursor {
  private final Object[] path;
  private int length;

  public PathCursor(int capacity) {
    super();
    path = new Object[capacity];
    length = 0;
  }

  public PathCursor(Object[] path, int capacity) {
    super();
    assert path.length <= capacity;
    this.path = new Object[capacity];
    System.arraycopy(path, 0, this.path, 0, path.length);
    length = path.length;
  }

  private PathCursor(PathCursor that) {
    super();
    path = Arrays.copyOf(that.path, that.path.length);
    length = that.length;
  }

  public PathCursor push(String name) {
    assert length < path.length;
    path[length] = name;
    length += 1;
    return this;
  }

  public PathCursor push(int index) {
    assert length < path.length;
    path[length] = index;
    length += 1;
    return this;
  }

  public void pop() {
    if (length > 0) {
      length -= 1;
    }
  }

  public void advance() {
    if (length > 0) {
      Object last = path[length - 1];
      if (last instanceof Integer) {
        path[length - 1] = (int) last + 1;
      } else {
        length -= 1;
      }
    }
  }

  public PathCursor copy() {
    return new PathCursor(this);
  }

  public int length() {
    return length;
  }

  Object get(int i) {
    return path[i];
  }

  public String toString(String prefix) {
    StringBuilder sb = new StringBuilder(prefix);
    for (int i = 0; i < length; i++) {
      sb.append('.');
      sb.append(String.valueOf(path[i]).replace(".", "\\."));
    }
    return sb.toString();
  }

  public Object[] toPath() {
    return Arrays.copyOf(path, length);
  }
}
