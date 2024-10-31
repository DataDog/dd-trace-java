package datadog.trace.payloadtags.json;

import java.util.Arrays;

/** Represents a mutable path in a JSON-like structure. */
public class PathCursor {
  private final Object[] path;
  private int levels;

  public PathCursor(int capacity) {
    super();
    path = new Object[capacity];
    levels = 0;
  }

  public PathCursor(Object[] path, int capacity) {
    super();
    assert path.length <= capacity;
    this.path = new Object[capacity];
    System.arraycopy(path, 0, this.path, 0, path.length);
    levels = path.length;
  }

  private PathCursor(PathCursor that) {
    super();
    path = Arrays.copyOf(that.path, that.path.length);
    levels = that.levels;
  }

  public PathCursor push(String name) {
    assert levels < path.length;
    path[levels] = name;
    levels += 1;
    return this;
  }

  public PathCursor push(int index) {
    assert levels < path.length;
    path[levels] = index;
    levels += 1;
    return this;
  }

  public void pop() {
    if (levels > 0) {
      levels -= 1;
    }
  }

  public void advance() {
    if (levels > 0) {
      Object last = path[levels - 1];
      if (last instanceof Integer) {
        path[levels - 1] = (int) last + 1;
      } else {
        levels -= 1;
      }
    }
  }

  public PathCursor copy() {
    return new PathCursor(this);
  }

  public int levels() {
    return levels;
  }

  Object jsonPathSegment(int i) {
    if (i == 0) {
      // root segment
      return null;
    }
    return path[i - 1];
  }

  public String toString(String prefix) {
    StringBuilder sb = new StringBuilder(prefix);
    for (int i = 0; i < levels; i++) {
      sb.append('.');
      sb.append(String.valueOf(path[i]).replace(".", "\\."));
    }
    return sb.toString();
  }

  public Object[] toPath() {
    return Arrays.copyOf(path, levels);
  }
}
