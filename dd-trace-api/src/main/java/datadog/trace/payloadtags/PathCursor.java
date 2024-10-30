package datadog.trace.payloadtags;

import java.util.Arrays;

/** Represents a mutable path in a JSON-like structure with an optional attached value. */
public class PathCursor {
  private final Object[] parts;
  private int size;
  private final Object value;

  public PathCursor(int capacity) {
    super();
    assert capacity > 0;
    parts = new Object[capacity];
    value = null;
  }

  private PathCursor(PathCursor that, Object value) {
    super();
    parts = Arrays.copyOf(that.parts, that.length());
    size = that.size;
    this.value = value;
  }

  public PathCursor push(String name) {
    assert size < parts.length;
    parts[size] = name.replace(".", "\\.");
    size += 1;
    return this;
  }

  public PathCursor push(int index) {
    assert value == null;
    assert size < parts.length;
    parts[size] = index;
    size += 1;
    return this;
  }

  public void pop() {
    assert value == null;
    if (size > 0) {
      size -= 1;
    }
  }

  public void advance() {
    assert value == null;
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
    return new PathCursor(this, null);
  }

  public int length() {
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

  public Object attachedValue() {
    return value;
  }

  public PathCursor withValue(Object value) {
    return new PathCursor(this, value);
  }
}
