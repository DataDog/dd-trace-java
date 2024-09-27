package datadog.trace.payloadtags.json;

import java.util.Arrays;

public class JsonPointer {
  private final Object[] parts;
  private int size;

  public JsonPointer(int capacity) {
    super();
    assert capacity > 0;
    parts = new Object[capacity];
  }

  private JsonPointer(JsonPointer that) {
    super();
    parts = Arrays.copyOf(that.parts, that.length());
    size = that.size;
  }

  public JsonPointer name(String value) {
    assert size < parts.length;
    parts[size] = value.replace(".", "\\.");
    size += 1;
    return this;
  }

  public JsonPointer index(int value) {
    assert size < parts.length;
    parts[size] = value;
    size += 1;
    return this;
  }

  public void beginArray() {
    index(0);
  }

  public void endArray() {
    if (size > 0) {
      size -= 1;
    }
  }

  public void endValue() {
    if (size > 0) {
      Object last = parts[size - 1];
      if (last instanceof Integer) {
        parts[size - 1] = (int) last + 1;
      } else {
        size -= 1;
      }
    }
  }

  public JsonPointer copy() {
    return new JsonPointer(this);
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
}
