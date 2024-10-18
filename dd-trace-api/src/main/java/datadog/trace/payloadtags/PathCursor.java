package datadog.trace.payloadtags;

import java.util.Arrays;

/** Represents a mutable path in a JSON-like structure with an optional attached object. */
public class PathCursor {
  private final Object[] parts;
  private int size;
  private Object value;

  public PathCursor(int capacity) {
    super();
    assert capacity > 0;
    parts = new Object[capacity];
  }

  private PathCursor(PathCursor that) {
    super();
    parts = Arrays.copyOf(that.parts, that.length());
    size = that.size;
    value = that.value;
  }

  public PathCursor push(String value) {
    assert size < parts.length;
    parts[size] = value.replace(".", "\\.");
    size += 1;
    return this;
  }

  public PathCursor push(int value) {
    assert size < parts.length;
    parts[size] = value;
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

  public PathCursor attachValue(Object value) {
    this.value = value;
    return this;
  }
}
