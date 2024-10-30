package datadog.trace.payloadtags.json;

import java.util.Arrays;

/** Represents a mutable path in a JSON-like structure. */
public class PathCursor {
  private final Object[] parts;
  private int size;

  public PathCursor(int capacity) {
    super();
    parts = new Object[capacity];
    size = 0;
  }

  public PathCursor(Object[] path, int capacity) {
    super();
    assert path.length <= capacity;
    parts = new Object[capacity];
    System.arraycopy(path, 0, parts, 0, path.length);
    size = path.length;
  }

  private PathCursor(PathCursor that) {
    super();
    parts = Arrays.copyOf(that.parts, that.parts.length);
    size = that.size;
  }

  public PathCursor push(String name) {
    assert size < parts.length;
    parts[size] = name.replace(".", "\\.");
    size += 1;
    return this;
  }

  public PathCursor push(int index) {
    assert size < parts.length;
    parts[size] = index;
    size += 1;
    return this;
  }

  public void pop() {
    if (size > 0) {
      size -= 1;
    }
  }

  public void advance() {
    if (size > 0) {
      Object last = parts[size - 1];
      if (last instanceof Integer) {
        parts[size - 1] = (int) last + 1;
      } else {
        size -= 1;
      }
    }
  }

  public PathCursor copy() {
    return new PathCursor(this);
  }

  public int depth() {
    return size + 1;
  }

  public Object get(int i) {
    if (i == 0) {
      return null;
    }
    return parts[i - 1];
  }

  public String dotted(String prefix) {
    StringBuilder sb = new StringBuilder(prefix);
    for (int i = 0; i < size; i++) {
      sb.append('.');
      sb.append(parts[i]);
    }
    return sb.toString();
  }

  public Object[] toPath() {
    return Arrays.copyOf(parts, size);
  }
}
