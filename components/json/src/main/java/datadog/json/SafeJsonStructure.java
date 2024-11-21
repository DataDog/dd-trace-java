package datadog.json;

import static java.util.stream.Collectors.joining;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * This {@link JsonStructure} performs minimal structure checks to ensure the built JSON is
 * coherent.
 */
class SafeJsonStructure implements JsonStructure {
  private final Deque<Boolean> structure;
  private boolean complete;

  SafeJsonStructure() {
    this.structure = new ArrayDeque<>();
    this.complete = false;
  }

  @Override
  public void beginObject() {
    if (this.complete) {
      throw new IllegalStateException("Object is complete");
    }
    this.structure.add(true);
  }

  @Override
  public boolean objectStarted() {
    return !this.structure.isEmpty() && this.structure.peekLast();
  }

  @Override
  public void endObject() {
    if (!objectStarted()) {
      throw new IllegalStateException("Object not started");
    }
    this.structure.removeLast();
    if (this.structure.isEmpty()) {
      this.complete = true;
    }
  }

  @Override
  public void beginArray() {
    if (this.complete) {
      throw new IllegalStateException("Object is complete");
    }
    this.structure.offer(false);
  }

  @Override
  public boolean arrayStarted() {
    return !this.structure.isEmpty() && !this.structure.peekLast();
  }

  @Override
  public void endArray() {
    if (!arrayStarted()) {
      throw new IllegalStateException("Array not started");
    }
    this.structure.removeLast();
    if (this.structure.isEmpty()) {
      this.complete = true;
    }
  }

  @Override
  public void addName() {
    if (!objectStarted()) {
      throw new IllegalStateException("Object not started");
    }
  }

  @Override
  public void addValue() {
    if (this.complete) {
      throw new IllegalStateException("Object is complete");
    }
    if (this.structure.isEmpty()) {
      this.complete = true;
    }
  }

  @Override
  public String toString() {
    return (this.complete ? "complete" : "")
        + this.structure.stream()
            .map(b -> b ? "object start" : "array start")
            .collect(joining(","));
  }
}
